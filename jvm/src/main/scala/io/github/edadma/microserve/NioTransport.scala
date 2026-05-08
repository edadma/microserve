package io.github.edadma.microserve

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, ServerSocketChannel, SocketChannel}
import scala.concurrent.{ExecutionContext, Future, Promise}

/** Adapter from the cross-platform [[ServerTransport]]/[[ConnectionTransport]]
  * interface to the JVM's `java.nio` selector + channels, driven by the
  * internal [[EventLoop]]. The shared `Server`/`ConnectionState` does the HTTP
  * work; this file is purely about reading/writing bytes.
  */
private[microserve] class NioServerTransport(loop: EventLoop) extends ServerTransport:
  private val channel = ServerSocketChannel.open()
  private var key: SelectionKey = null
  private var acceptHandler: ConnectionTransport => Unit = _ => ()

  def actualPort: Int = channel.socket().getLocalPort

  def onAccept(handler: ConnectionTransport => Unit): Unit =
    acceptHandler = handler

  def listen(host: String, port: Int)(onListening: () => Unit): Unit =
    channel.configureBlocking(false)
    channel.bind(new InetSocketAddress(host, port))
    key = loop.register(channel, SelectionKey.OP_ACCEPT, new AcceptHandler)
    loop.ref()
    loop.nextTick(onListening)

  def close(): Unit =
    if key != null then
      key.cancel()
      key = null
    try channel.close()
    catch case _: Exception => ()
    loop.unref()

  private class AcceptHandler extends SelectionKeyHandler:
    def handle(k: SelectionKey): Unit =
      val server = k.channel().asInstanceOf[ServerSocketChannel]
      val client = server.accept()
      if client != null then
        client.configureBlocking(false)
        // The transport's ctor takes its own ref + registers OP_READ.
        val transport = new NioConnectionTransport(loop, client)
        acceptHandler(transport)
end NioServerTransport

/** Per-connection NIO transport. Read/write/close all run from the event loop
  * thread, so we don't need any synchronization beyond what the loop already
  * provides.
  *
  * Reads use a single 8KiB buffer reused across reads; writes copy the bytes
  * once into a [[ByteBuffer]] and drain it with the channel in a tight loop.
  * For dev-preview workloads (juicer's use case) this is fine; if a single
  * response ever exceeds the socket send buffer the write loop blocks the
  * event loop briefly, but we never call out to the kernel-side `select` for
  * write readiness — a deliberate simplification.
  */
/** @param reusedKey If `Some(k)`, this transport is being created from a
  *                  channel whose `SelectionKey` is already registered with
  *                  the selector (i.e. an OP_CONNECT promotion). We *must
  *                  not* call `selector.register` again on the same channel
  *                  while the prior key is still live — that races with the
  *                  selector's bookkeeping and raises `CancelledKeyException`.
  *                  Instead we adopt the existing key, swap the attachment to
  *                  our `ReadHandler`, and flip the interest ops to `OP_READ`.
  */
private[microserve] class NioConnectionTransport(
    loop: EventLoop,
    channel: SocketChannel,
    reusedKey: Option[SelectionKey] = None,
) extends ConnectionTransport:
  private val readBufferSize = 8192
  private val readBuffer = ByteBuffer.allocate(readBufferSize)

  private var readHandler: Array[Byte] => Unit = _ => ()
  private var closeHandler: () => Unit = () => ()
  private var closed = false

  // One ref per live connection — released in closeInternal. Centralising
  // this here means the accept and connect paths don't have to track refs.
  loop.ref()

  private val key: SelectionKey = reusedKey match
    case Some(k) =>
      k.attach(new ReadHandler)
      k.interestOps(SelectionKey.OP_READ)
      // Selector won't notice the new interest set until it next polls.
      loop.selector.wakeup()
      k
    case None =>
      loop.register(channel, SelectionKey.OP_READ, new ReadHandler)

  def remoteAddress: String =
    try channel.getRemoteAddress.toString
    catch case _: Exception => "unknown"

  def onRead(handler: Array[Byte] => Unit): Unit = readHandler = handler

  def onClose(handler: () => Unit): Unit = closeHandler = handler

  /** Synchronous best-effort write. Returns a successful Future if all bytes
    * make it into the socket; returns a failed Future if the channel is dead.
    * In either case `onClose` is fired in the failure path so the
    * `ConnectionState` lifecycle continues correctly.
    */
  def write(bytes: Array[Byte]): Future[Unit] =
    if closed then return Future.failed(new java.io.IOException("connection closed"))
    val bb = ByteBuffer.wrap(bytes)
    try
      while bb.hasRemaining do channel.write(bb)
      Future.successful(())
    catch
      case e: Exception =>
        // Surface the error to the connection lifecycle.
        closeInternal()
        Future.failed(e)

  def close(): Unit = closeInternal()

  private def closeInternal(): Unit =
    if closed then return
    closed = true
    if key.isValid then key.cancel()
    try channel.close()
    catch case _: Exception => ()
    loop.unref()
    closeHandler()

  private class ReadHandler extends SelectionKeyHandler:
    def handle(k: SelectionKey): Unit =
      if !k.isReadable || closed then return
      readBuffer.clear()
      val bytesRead =
        try channel.read(readBuffer)
        catch
          case _: Exception =>
            closeInternal()
            return

      if bytesRead == -1 then
        closeInternal()
        return

      readBuffer.flip()
      val arr = new Array[Byte](bytesRead)
      readBuffer.get(arr)
      readHandler(arr)
end NioConnectionTransport

/** Outbound TCP — used by tests (and any future juicer feature that wants to
  * speak HTTP). Returns the connection only once the underlying socket has
  * actually finished `connect()`, so callers can wire `onRead`/`onClose`
  * synchronously and trust that bytes won't be dropped.
  */
private[microserve] class NioClientConnect(loop: EventLoop):
  def connect(host: String, port: Int): Future[ConnectionTransport] =
    val promise = Promise[ConnectionTransport]()
    try
      val channel = SocketChannel.open()
      channel.configureBlocking(false)
      val immediate = channel.connect(new InetSocketAddress(host, port))
      if immediate then
        // Loopback can complete synchronously on some platforms, in which
        // case OP_CONNECT will never be reported. Hand off to a fresh
        // transport (no key to reuse) and resolve the promise immediately.
        promise.success(new NioConnectionTransport(loop, channel))
      else
        val handler = new ConnectHandler(channel, promise)
        // Loop ref while the OP_CONNECT key is live; ConnectHandler hands
        // off to the transport (which takes its own ref) and we drop ours
        // there.
        loop.register(channel, SelectionKey.OP_CONNECT, handler)
        loop.ref()
    catch case e: Exception => promise.failure(e)
    promise.future

  private class ConnectHandler(channel: SocketChannel, promise: Promise[ConnectionTransport]) extends SelectionKeyHandler:
    def handle(k: SelectionKey): Unit =
      try
        if channel.finishConnect() then
          // Reuse the existing OP_CONNECT key as the transport's OP_READ key.
          // Cancelling and re-registering on the same channel within one
          // select cycle deadlocks on `CancelledKeyException`.
          loop.unref() // matches the ref in connect()'s OP_CONNECT path
          val transport = new NioConnectionTransport(loop, channel, reusedKey = Some(k))
          promise.success(transport)
      catch
        case e: Exception =>
          k.cancel()
          loop.unref()
          try channel.close()
          catch case _: Exception => ()
          promise.failure(e)
end NioClientConnect
