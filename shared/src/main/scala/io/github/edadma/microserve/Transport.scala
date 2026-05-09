package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future}

/** The platform-specific seam. Each platform (JVM/JS/Native) supplies an
  * implementation of [[Runtime]] that the shared `Server` uses for transports,
  * timers, and a `Future` execution context that runs continuations on the
  * platform's event loop.
  *
  * Users normally don't construct a [[Runtime]] directly: each platform
  * exposes a `given Runtime` at package level, picked up implicitly by
  * `createServer`.
  */
trait Runtime:
  /** ExecutionContext for `Future` continuations on the platform's event loop. */
  def executionContext: ExecutionContext

  /** Schedulers + timers for this loop. */
  def timers: Timers

  /** Construct a fresh listening transport (one per server). */
  def newServerTransport(): ServerTransport

  /** Open an outbound TCP connection. The returned [[ConnectionTransport]]'s
    * read/close handlers should be wired up before bytes start arriving.
    */
  def connect(host: String, port: Int): Future[ConnectionTransport]

  /** Construct a fresh [[FsWatcher]]. Each watcher owns its own platform
    * resources; call `close()` on it when finished.
    *
    * The watcher delivers events on this runtime's `executionContext`, so
    * handlers run on the same thread as HTTP request handlers and Future
    * continuations.
    */
  def newFsWatcher(): FsWatcher

  /** Drive the loop until all servers have closed and all connections drained.
    *
    *   - JVM: blocks on the internal `EventLoop.run()`.
    *   - Native: blocks on libuv's `uv_run`.
    *   - JS: no-op (Node's loop runs automatically).
    */
  def run(): Unit

  /** Stop the loop early. */
  def stop(): Unit
end Runtime

/** A scheduling abstraction every platform must provide. */
trait Timers:
  /** Run `callback` after `delayMs` milliseconds. The returned function cancels
    * the pending fire (idempotent).
    */
  def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit

/** A bound TCP listener. */
trait ServerTransport:
  def listen(host: String, port: Int)(onListening: () => Unit): Unit

  /** Port we're listening on. Only meaningful after `listen` callback fires. */
  def actualPort: Int

  /** Wire a callback that's invoked once per accepted connection. Must be
    * called before `listen` for a guarantee of receiving every accept.
    */
  def onAccept(handler: ConnectionTransport => Unit): Unit

  /** Stop accepting; existing connections are not affected. */
  def close(): Unit

/** An accepted or dialed TCP connection. The shared `ConnectionState` wires
  * `onRead`/`onClose`, then drives writes through `write`.
  */
trait ConnectionTransport:
  def remoteAddress: String

  /** Begin delivering received bytes to `handler`. Each call delivers a
    * non-empty chunk; EOF / error is signalled via `onClose`.
    */
  def onRead(handler: Array[Byte] => Unit): Unit

  /** Called once when the underlying socket is closed (locally or remotely)
    * for any reason. Implementations MUST invoke this exactly once per
    * connection.
    */
  def onClose(handler: () => Unit): Unit

  /** Send `bytes`. The returned `Future` completes when the transport has
    * accepted the bytes for delivery (not necessarily when they reach the peer).
    * Failures during write should fail the future and trigger `onClose`.
    */
  def write(bytes: Array[Byte]): Future[Unit]

  /** Close the connection. Idempotent. Triggers the registered `onClose`
    * handler if not already triggered.
    */
  def close(): Unit
end ConnectionTransport
