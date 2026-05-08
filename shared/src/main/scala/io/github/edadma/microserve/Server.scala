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

  // Each accepted ConnectionTransport spawns a ConnectionState; teardown is
  // observed via ConnectionState's own close hook so we can drain accurately.
  transport.onAccept { connTransport =>
    val state = new ConnectionState(
      transport = connTransport,
      handler = handler,
      timers = timers,
      onConnectionClosed = onConnectionClosed,
      isServerClosing = () => _closing,
    )
    connections += state
  }

  def isListening: Boolean = _listening

  def actualPort: Int = transport.actualPort

  /** Bind and start accepting. `onListening` fires once the socket is bound.
    * Calling more than once is undefined behaviour (matches Node.js).
    */
  def listen(port: Int, host: String = "0.0.0.0")(onListening: () => Unit = () => ()): Unit =
    transport.listen(host, port) { () =>
      _listening = true
      onListening()
    }

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
