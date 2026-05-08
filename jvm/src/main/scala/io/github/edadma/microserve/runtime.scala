package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future}

/** JVM runtime: owns one [[EventLoop]] per process, hooks all microserve I/O
  * + timers + Future continuations onto it.
  *
  * Exposed as `given Runtime` at package level so that
  * `import io.github.edadma.microserve.given` (or just being in scope) is all
  * a user needs.
  */
/** JVM runtime. Auto-starts a daemon thread that drives the [[EventLoop]], so
  * the loop is "always on" the moment a [[JvmRuntime]] exists — matching
  * Node's and libuv's behaviour on the other two platforms. The loop is kept
  * alive by a phantom ref taken in the constructor; calling [[run]] drops
  * that ref and waits for the daemon to finish (i.e. for refCount to drop to
  * zero, which happens when the last server has closed and the last connection
  * has drained).
  *
  * Why a daemon: tests that use `AsyncFreeSpec` return a `Future[Assertion]`
  * to ScalaTest immediately — there's no spot to call `run()` synchronously.
  * Without a background driver, all work scheduled on `executionContext`
  * (Future continuations, accept handlers, …) would queue and never fire.
  *
  * Thread safety: the [[EventLoop]] uses a `ConcurrentLinkedQueue` for the
  * microtask/immediate queues and an `AtomicInteger` for the ref-count, so
  * cross-thread `nextTick`, `unref`, and `executionContext.execute` are all
  * safe. `setTimeout`'s priority queue is NOT thread-safe — callers must only
  * invoke it from the loop thread. In practice every `setTimeout` site
  * (idle-timeout in `ConnectionState`) runs from a callback already on the
  * loop, so this constraint holds.
  */
private[microserve] class JvmRuntime extends Runtime:
  private val loop = new EventLoop

  // Phantom ref keeps the loop alive even when no work is queued, so the
  // daemon thread doesn't exit prematurely between user-thread setup calls.
  loop.ref()

  private val driver: Thread =
    val t = new Thread(() => loop.run(), "microserve-eventloop")
    t.setDaemon(true)
    t.start()
    t

  def executionContext: ExecutionContext = loop.executionContext

  val timers: Timers = new Timers:
    def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit =
      loop.setTimeout(delayMs)(callback)

  def newServerTransport(): ServerTransport = new NioServerTransport(loop)

  def connect(host: String, port: Int): Future[ConnectionTransport] =
    new NioClientConnect(loop).connect(host, port)

  /** Drop the phantom ref and wait for the daemon to finish. The loop exits
    * naturally once all `Server`s have closed and their connections drained.
    * Calling this multiple times is safe — second call returns immediately
    * because the daemon is already gone.
    */
  def run(): Unit =
    if !driver.isAlive then return
    if loop.refCount > 0 then loop.unref() // drop the phantom
    driver.join()

  def stop(): Unit = loop.stop()

  /** Escape hatch for tests that need a separate thread to run the loop. */
  private[microserve] def underlying: EventLoop = loop

given Runtime = JvmRuntime()
