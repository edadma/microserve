package io.github.edadma.microserve

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.{Uint8Array, byteArray2Int8Array, Int8Array}
import scala.concurrent.{Future, Promise}

/** Minimal facades over Node's `net` module. We deliberately use `net` (raw
  * TCP) rather than `http` so the shared [[HTTPRequestParser]] sees the same
  * byte stream on every platform. Keeping the facade tiny avoids pulling in
  * scalajs-dom or `@types/node`-style libraries.
  */
private[microserve] object NodeNet:
  @js.native @JSImport("net", JSImport.Namespace)
  object net extends js.Object:
    def createServer(connectionListener: js.Function1[Socket, Unit]): NetServer = js.native
    def connect(port: Int, host: String): Socket = js.native

  @js.native
  trait NetServer extends js.Object:
    def listen(port: Int, hostname: String, callback: js.Function0[Unit]): NetServer = js.native
    def close(callback: js.Function0[Unit]): NetServer = js.native
    def address(): js.Dynamic = js.native

  @js.native
  trait Socket extends js.Object:
    def on(event: String, listener: js.Function1[js.Any, Unit]): Socket = js.native
    def write(chunk: Uint8Array): Boolean = js.native
    def end(): Unit = js.native
    def destroy(): Unit = js.native
    def remoteAddress: js.UndefOr[String] = js.native
    def remotePort: js.UndefOr[Int] = js.native
end NodeNet

import NodeNet.*

private[microserve] class NetServerTransport extends ServerTransport:
  private var server: NetServer = null
  private var acceptHandler: ConnectionTransport => Unit = _ => ()
  private var _actualPort: Int = 0

  def actualPort: Int = _actualPort

  def onAccept(handler: ConnectionTransport => Unit): Unit = acceptHandler = handler

  def listen(host: String, port: Int)(onListening: () => Unit): Unit =
    server = net.createServer { (socket: Socket) =>
      acceptHandler(new NetConnectionTransport(socket))
    }
    server.listen(
      port,
      host,
      { () =>
        // `address()` returns `{ port, family, address }` for an IP listener.
        val addr = server.address()
        if addr != null && !js.isUndefined(addr) then
          _actualPort = addr.port.asInstanceOf[Int]
        onListening()
      },
    )

  def close(): Unit =
    if server != null then
      server.close(() => ())
      server = null
end NetServerTransport

/** Wraps a single `net.Socket`. Node delivers `data` events as Buffers
  * (Uint8Array-like); we copy each into a Scala `Array[Byte]` once and let the
  * shared `ConnectionState` do the rest.
  */
private[microserve] class NetConnectionTransport(socket: Socket) extends ConnectionTransport:
  private var readHandler: Array[Byte] => Unit = _ => ()
  private var closeHandler: () => Unit = () => ()

  // Two different flags:
  //   `closeRequested` makes our `close()` idempotent (don't double-end).
  //   `closeFired` makes the user's `closeHandler` fire exactly once.
  // Conflating these is the bug that hangs the test framework: when the user
  // calls `close()`, we'd set the conflated flag, and then Node's 'close'
  // event arrives but is suppressed because the flag is already set, so the
  // handler never fires and the higher-level drain never completes.
  private var closeRequested = false
  private var closeFired = false

  socket.on("data", { (chunk: js.Any) =>
    val u8 = chunk.asInstanceOf[Uint8Array]
    val arr = new Array[Byte](u8.length)
    var i = 0
    while i < u8.length do
      arr(i) = u8(i).toByte
      i += 1
    readHandler(arr)
  })

  socket.on("close", { (_: js.Any) =>
    if !closeFired then
      closeFired = true
      closeHandler()
  })

  socket.on("error", { (_: js.Any) =>
    // 'close' fires right after 'error'; let the 'close' branch run.
    ()
  })

  def remoteAddress: String =
    val host = socket.remoteAddress.getOrElse("unknown")
    val port = socket.remotePort.getOrElse(0)
    s"/$host:$port"

  def onRead(handler: Array[Byte] => Unit): Unit = readHandler = handler

  def onClose(handler: () => Unit): Unit = closeHandler = handler

  def write(bytes: Array[Byte]): Future[Unit] =
    if closeRequested then return Future.failed(new RuntimeException("connection closed"))
    val u8 = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      u8(i) = (bytes(i) & 0xFF).toShort
      i += 1
    socket.write(u8)
    Future.successful(())

  def close(): Unit =
    if closeRequested then return
    closeRequested = true
    socket.end()
end NetConnectionTransport

private[microserve] class NetClientConnect:
  def connect(host: String, port: Int): Future[ConnectionTransport] =
    val promise = Promise[ConnectionTransport]()
    val socket = net.connect(port, host)
    val transport = new NetConnectionTransport(socket)
    socket.on("connect", { (_: js.Any) => promise.success(transport) })
    socket.on("error", { (err: js.Any) =>
      // Only resolve if not already resolved by 'connect'.
      promise.tryFailure(new RuntimeException(s"connect failed: $err"))
    })
    promise.future
end NetClientConnect
