package io.github.edadma.microserve

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** Cross-platform HTTP server. The interesting work — parser feeding, request
  * dispatch, idle timeout, drain accounting — is platform-agnostic; the only
  * platform-specific piece is the [[ServerTransport]]/[[ConnectionTransport]]
  * supplied via the implicit [[Runtime]].
  *
  * Construct via the package-level [[createServer]] factory.
  */
class Server private[microserve] (handler: RequestHandler, runtime: Runtime):
  private val transport = runtime.newServerTransport()
  private val timers = runtime.timers
  private given ExecutionContext = runtime.executionContext

  private val connections = mutable.Set[ConnectionState]()
  private var _closing = false
  private var _onDrain: Option[() => Unit] = None
  private var _listening = false
  // Updated by `listen` before any connections can arrive; ConnectionState
  // reads this when constructing its idle-timeout. 30s matches Node's default.
  private var _idleTimeoutMs: Long = 30000L

  // Each accepted ConnectionTransport spawns a ConnectionState; teardown is
  // observed via ConnectionState's own close hook so we can drain accurately.
  transport.onAccept { connTransport =>
    val state = new ConnectionState(
      transport = connTransport,
      handler = handler,
      timers = timers,
      onConnectionClosed = onConnectionClosed,
      isServerClosing = () => _closing,
      idleTimeoutMs = _idleTimeoutMs,
    )
    connections += state
  }

  def isListening: Boolean = _listening

  def actualPort: Int = transport.actualPort

  /** Bind and start accepting. `onListening` fires once the socket is bound;
    * `onError` fires (asynchronously, on the loop) if the bind fails — most
    * commonly because the port is already in use. After `onError`, this
    * server is unusable; construct a fresh one if you want to retry on a
    * different port.
    *
    * `idleTimeoutMs` controls how long an established keep-alive connection
    * may sit idle (no bytes either way) before microserve closes it. Defaults
    * to 30s, matching Node's `server.keepAliveTimeout`/`headersTimeout`. Long-
    * polling endpoints whose wait window approaches the timeout should either
    * raise this value or shorten their wait so the server doesn't kill the
    * socket out from under them.
    *
    * Calling more than once is undefined behaviour (matches Node.js).
    */
  def listen(port: Int, host: String = "0.0.0.0", idleTimeoutMs: Long = 30000L)(
      onListening: () => Unit          = () => (),
      onError:     Throwable => Unit   = _ => (),
  ): Unit =
    _idleTimeoutMs = idleTimeoutMs
    // Normalise the two common dev-server defaults to canonical IPv4 strings
    // so every transport sees the same numeric address. Without this, Node on
    // macOS picks ::1 (IPv6) for "localhost", and clients that go straight to
    // 127.0.0.1 then get ECONNREFUSED. Arbitrary hostnames pass through to
    // the transport, which is responsible for resolving them — JVM and Node
    // both do DNS implicitly; the Native transport routes through libuv's
    // `uv_getaddrinfo`.
    val resolvedHost = host.toLowerCase match
      case "localhost" => "127.0.0.1"
      case ""          => "0.0.0.0"
      case h           => h
    transport.listen(resolvedHost, port)(
      () =>
        _listening = true
        onListening(),
      e => onError(e),
    )

  private def onConnectionClosed(state: ConnectionState): Unit =
    connections -= state
    if _closing && connections.isEmpty then
      _onDrain.foreach(_())
      _onDrain = None

  /** Stop accepting new connections, close idle ones immediately, let active
    * ones finish. `onDrain` fires once the last connection is gone. The
    * server can be `close`d while never `listen`ed (no-op).
    */
  def close(onDrain: () => Unit = () => ()): Unit =
    if _closing then return
    _closing = true

    transport.close()
    connections.toList.foreach(_.shutdownIfIdle())

    if connections.isEmpty then onDrain()
    else _onDrain = Some(onDrain)

  /** Drive the underlying event loop until everything completes. JVM/Native
    * block here; JS returns immediately because Node owns its loop.
    */
  def run(): Unit = runtime.run()
end Server

object Server:

  /** Bind a fresh server starting at `startPort`, climbing one port at a time
    * if the bind fails with [[BindError.AddressInUse]]. Calls `onBound` with
    * the successfully-bound server (and the actual port chosen). Calls
    * `onError` only when the failure is *not* a port conflict, or when the
    * retry budget is exhausted.
    *
    * `onPortBumped(busyPort, nextPort)` fires once per skipped port so dev
    * tools can log "port N is in use; trying N+1…" without having to hook
    * the inner retry loop themselves. Default is a no-op.
    *
    * Each retry constructs a fresh [[Server]] (and a fresh transport):
    * microserve's transports are one-shot — once the underlying socket has
    * failed to bind, the bookkeeping is unsafe to reuse.
    */
  def bindWithRetry(handler: RequestHandler)(
      startPort:     Int,
      host:          String        = "0.0.0.0",
      retries:       Int           = 20,
      idleTimeoutMs: Long          = 30000L,
      onPortBumped:  (Int, Int) => Unit = (_, _) => (),
  )(
      onBound: (Server, Int) => Unit,
      onError: BindError => Unit          = _ => (),
  )(using runtime: Runtime): Unit =
    def attempt(port: Int, remaining: Int): Unit =
      val server = new Server(handler, runtime)
      server.listen(port, host, idleTimeoutMs)(
        onListening = () => onBound(server, server.actualPort),
        onError = {
          case _: BindError.AddressInUse if remaining > 0 =>
            onPortBumped(port, port + 1)
            attempt(port + 1, remaining - 1)
          case e: BindError =>
            onError(e)
          case e =>
            // Defensive: a transport that didn't translate to BindError. We
            // wrap so the user-facing onError signature stays typed.
            onError(BindError.Other(e))
        },
      )
    attempt(startPort, retries)
  end bindWithRetry

end Server
