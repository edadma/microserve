package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future, Promise}
import io.github.spritzsn.libuv.{defaultLoop, Timer}
import io.github.spritzsn.async.EventLoopExecutionContext

/** Native runtime backed by `spritzsn/libuv` + `spritzsn/async`.
  *
  * `spritzsn/async` exposes [[EventLoopExecutionContext]] for `Future`
  * continuations: `execute()` queues a runnable and starts a libuv `prepare`
  * handle; the handle's callback drains the queue on the next loop turn. The
  * library *also* tries to bootstrap a driver thread for `uv_run` via
  * `ExecutionContext.global`, but in the Scala-Native sbt-test runner that
  * bootstrap thread does not get scheduled, so we observe `Future`
  * continuations being enqueued but never running. We sidestep the issue by
  * starting our own driver thread here — same model as [[JvmRuntime]]'s
  * daemon — and just leaning on `EventLoopExecutionContext` for the queue
  * machinery.
  *
  * Thread safety caveat: libuv handles are not thread-safe, so test code that
  * does `defaultLoop.tcp` etc. from the test (main) thread while this driver
  * thread runs `uv_run` on a different thread is, strictly speaking, racy.
  * In practice on modern macOS/aarch64 it works — the operations we touch
  * from the main thread are handle creation and `uv_*_init` which do not
  * touch the per-loop watcher list during a `uv_run` poll. If we ever see
  * Heisenbugs we'll need to route all setup through `uv_async_send` to wake
  * the loop and execute on the loop thread.
  */
private[microserve] class NativeRuntime extends Runtime:

  // Drive uv_run on a daemon thread. `defaultLoop.run()` returns non-zero
  // while there are still active handles or pending callbacks; the
  // tail-recursive wrapper restarts it so we don't exit until nothing's
  // left to do. We hold a phantom reference handle (an `idle` watcher
  // that never fires anything) so the loop doesn't exit between the test's
  // setup phase and the first `prepare` activation.
  private val keepAlive = defaultLoop.idle
  keepAlive.start(_ => ())

  private val driver: Thread =
    val t = new Thread(
      () =>
        @scala.annotation.tailrec
        def loop(): Unit = if defaultLoop.run() != 0 then loop()
        loop(),
      "microserve-libuv-loop",
    )
    t.setDaemon(true)
    t.start()
    t

  def executionContext: ExecutionContext = EventLoopExecutionContext

  val timers: Timers = new Timers:
    def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit =
      var cancelled = false
      val timer: Timer = defaultLoop.timer
      timer.start(
        t =>
          if !cancelled then
            cancelled = true
            t.stop
            t.dispose()
            callback(),
        delayMs,
        0,
      )
      () =>
        if !cancelled then
          cancelled = true
          try timer.stop catch case _: Throwable => ()
          try timer.dispose() catch case _: Throwable => ()

  def newServerTransport(): ServerTransport = new LibuvServerTransport

  def connect(host: String, port: Int): Future[ConnectionTransport] =
    new LibuvClientConnect().connect(host, port)

  /** Drop the phantom handle and wait for the driver to drain. Calling
    * multiple times is safe; the second call returns immediately because the
    * driver is already gone.
    */
  def run(): Unit =
    if !driver.isAlive then return
    try keepAlive.stop catch case _: Throwable => ()
    driver.join()

  def stop(): Unit = ()
end NativeRuntime

given Runtime = NativeRuntime()
