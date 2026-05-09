package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.timers.SetTimeoutHandle

/** JS runtime: piggybacks on Node's libuv loop entirely. There is no separate
  * loop driver to construct — Node's loop runs on its own as long as there are
  * active handles (an open `net.Server`, a pending `setTimeout`, etc.).
  *
  * `executionContext` uses `js.Promise.resolve().then(...)` semantics via
  * Scala.js's default `JSExecutionContext`, which queues microtasks on the
  * Node event loop.
  */
private[microserve] class JsRuntime extends Runtime:

  def executionContext: ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  val timers: Timers = new Timers:
    def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit =
      val handle: SetTimeoutHandle =
        scala.scalajs.js.timers.setTimeout(delayMs.toDouble) { callback() }
      () => scala.scalajs.js.timers.clearTimeout(handle)

  def newServerTransport(): ServerTransport = new NetServerTransport

  def connect(host: String, port: Int): Future[ConnectionTransport] =
    new NetClientConnect().connect(host, port)

  def newFsWatcher(): FsWatcher = new NodeFsWatcher(executionContext)

  /** No-op: Node owns its loop. The presence of any active handle (server or
    * pending timer) keeps the process alive automatically.
    */
  def run(): Unit = ()

  /** No-op as well. Tests that need to force shutdown call `server.close(...)`
    * which removes the only active handle and lets Node exit normally.
    */
  def stop(): Unit = ()
end JsRuntime

given Runtime = JsRuntime()
