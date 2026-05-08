package io.github.edadma.microserve

import scala.collection.immutable.ArraySeq
import scala.concurrent.{Future, Promise}
import io.github.spritzsn.libuv as uv
import io.github.spritzsn.libuv.{TCP, Buffer, defaultLoop, eof, errName, strError}

/** Native transport built on `spritzsn/libuv`. The libuv loop is the same one
  * that `spritzsn/async`'s ExecutionContext drives, so `Future` continuations
  * scheduled by the shared `Server`/`ConnectionState` run on the same event
  * loop as the I/O — exactly the JVM/Node story, just with libuv instead of a
  * Selector or Node's internals.
  *
  * Memory management: libuv handles are heap-allocated via `loop.tcp` /
  * `loop.timer`. The wrapper's `close()` schedules an internal libuv `uv_close`
  * which fires `closeCallbackTCP` and frees the handle. We never `dispose()`
  * a TCP handle directly — that would skip the libuv close cycle.
  */
private[microserve] class LibuvServerTransport extends ServerTransport:
  // TCP is an opaque value class so it can't hold null — use an Option
  // sentinel for "not yet listening".
  private var server: Option[TCP] = None
  private var acceptHandler: ConnectionTransport => Unit = _ => ()
  private var _actualPort: Int = 0
  private var closed = false

  def actualPort: Int = _actualPort

  def onAccept(handler: ConnectionTransport => Unit): Unit = acceptHandler = handler

  def listen(host: String, port: Int)(onListening: () => Unit): Unit =
    val s = defaultLoop.tcp
    server = Some(s)
    s.bind(host, port, 0)
    s.listen(
      128,
      { (handle: TCP, status: Int) =>
        if status >= 0 && !closed then
          val client = defaultLoop.tcp
          handle.accept(client)
          val transport = new LibuvConnectionTransport(client)
          acceptHandler(transport)
        else if status < 0 then
          Console.err.println(s"libuv listen error: ${strError(status)}")
      },
    )

    // libuv has no direct "what port did we bind to" call wired into the
    // spritzsn wrapper's TCP class. For "port 0 → ephemeral" we'd need
    // `uv_tcp_getsockname` exposed; until then we just report the requested
    // port. Tests bind to fixed ports for that reason.
    _actualPort = port
    onListening()
  end listen

  def close(): Unit =
    if closed then return
    closed = true
    server.foreach { s => if !s.isClosing then s.close() }
end LibuvServerTransport

/** Wraps a single accepted (or dialed) TCP handle. */
private[microserve] class LibuvConnectionTransport(client: TCP) extends ConnectionTransport:
  private var readHandler: Array[Byte] => Unit = _ => ()
  private var closeHandler: () => Unit = () => ()
  private var closed = false
  private var reading = false

  // Cached peer name — `getPeerName` traps if the socket has been closed, so
  // we read it eagerly and stash it.
  private val cachedPeer: String =
    try client.getPeerName
    catch case _: Throwable => "unknown"

  // Begin reading immediately so we don't lose the first request bytes.
  startReading()

  private def startReading(): Unit =
    if !reading && !closed then
      reading = true
      client.readStart { (stream: TCP, size: Int, buf: Buffer) =>
        if size > 0 then
          val arr = buf.read(size)
          readHandler(arr)
        else if size < 0 then
          if size != eof then
            Console.err.println(s"libuv read error: ${errName(size)}: ${strError(size)}")
          internalClose()
      }

  def remoteAddress: String = cachedPeer

  def onRead(handler: Array[Byte] => Unit): Unit = readHandler = handler

  def onClose(handler: () => Unit): Unit = closeHandler = handler

  /** spritzsn's libuv `TCP.write` takes an `IndexedSeq[Byte]`. We wrap the
    * Array without copying via `ArraySeq.unsafeWrapArray`, then return a
    * resolved Future since the wrapper already malloc-copies into its libuv
    * buffer and frees on the libuv write callback. We don't expose a per-write
    * completion future today — the `Future.successful(())` matches the
    * "queued for delivery" semantics promised by [[ConnectionTransport.write]].
    */
  def write(bytes: Array[Byte]): Future[Unit] =
    if closed || client.isClosing then return Future.failed(new RuntimeException("connection closed"))
    if !client.isWritable then return Future.failed(new RuntimeException("connection not writable"))
    client.write(ArraySeq.unsafeWrapArray(bytes))
    Future.successful(())

  def close(): Unit = internalClose()

  private def internalClose(): Unit =
    if closed then return
    closed = true
    if reading then
      try client.readStop catch case _: Throwable => ()
      reading = false
    // Graceful shutdown then close. libuv's spritzsn wrapper does
    // `client.shutdown(_.close())`; if the client is already closing, we just
    // let libuv reclaim the handle on the next loop turn.
    if !client.isClosing then
      try client.shutdown(_.close())
      catch case _: Throwable => try client.close() catch case _: Throwable => ()
    closeHandler()
end LibuvConnectionTransport

/** Outbound TCP. Used by the cross-platform integration tests; not yet part of
  * the public API.
  */
private[microserve] class LibuvClientConnect:
  def connect(host: String, port: Int): Future[ConnectionTransport] =
    val promise = Promise[ConnectionTransport]()
    val client = defaultLoop.tcp
    try
      client.connect(host, port, { status =>
        if status == 0 then promise.success(new LibuvConnectionTransport(client))
        else
          try client.close() catch case _: Throwable => ()
          promise.failure(new RuntimeException(s"connect failed: ${strError(status)}"))
      })
    catch
      case e: Throwable =>
        try client.close() catch case _: Throwable => ()
        promise.failure(e)
    promise.future
end LibuvClientConnect
