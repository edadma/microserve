package io.github.edadma.microserve

import java.nio.channels.{Selector, SelectionKey, SelectableChannel}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.PriorityQueue
import scala.concurrent.ExecutionContext

/** Internal Node-style event loop for the JVM. Drives a single `Selector` for
  * I/O readiness, a min-heap of timers, and two FIFO queues for microtasks
  * (`nextTick`) and macrotasks (`setImmediate`). The loop terminates when the
  * ref-count drops to zero with no pending work.
  *
  * Not part of the public API — users construct a [[Server]] via
  * `createServer` and the JVM `Runtime` keeps its own loop internally.
  */
private[microserve] case class TimerEntry(id: Long, deadline: Long, callback: () => Unit)

private[microserve] class EventLoop:
  val selector: Selector = Selector.open()

  private val timers = PriorityQueue.empty[TimerEntry](
    using Ordering.by[TimerEntry, Long](_.deadline).reverse,
  )

  private val microtasks = new ConcurrentLinkedQueue[() => Unit]()
  private val immediates = new ConcurrentLinkedQueue[() => Unit]()

  private var running = false
  private var nextTimerId = 0L

  private val _refCount = new AtomicInteger(0)

  def ref(): Unit = _refCount.incrementAndGet()
  def unref(): Unit = _refCount.decrementAndGet()
  def refCount: Int = _refCount.get()

  val executionContext: ExecutionContext = new ExecutionContext:
    def execute(runnable: Runnable): Unit =
      microtasks.add(() => runnable.run())
      selector.wakeup()
    def reportFailure(cause: Throwable): Unit =
      cause.printStackTrace()

  def nextTick(callback: () => Unit): Unit =
    microtasks.add(callback)
    selector.wakeup()

  def setImmediate(callback: () => Unit): Unit =
    immediates.add(callback)
    selector.wakeup()

  def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit =
    var cancelled = false
    // `nextTimerId` and `timers` (a PriorityQueue) aren't thread-safe; ref()
    // and unref() are. Stamp the deadline now so the wait is measured from
    // the call site, then defer the ID + heap-enqueue to the loop thread by
    // riding on the (thread-safe) microtask queue.
    val deadline = System.currentTimeMillis() + delayMs
    ref()
    microtasks.add { () =>
      nextTimerId += 1
      val entry = TimerEntry(nextTimerId, deadline, () => {
        if !cancelled then
          cancelled = true
          unref()
          callback()
      })
      timers.enqueue(entry)
    }
    selector.wakeup()
    () =>
      if !cancelled then
        cancelled = true
        unref()

  def setInterval(intervalMs: Long)(callback: () => Unit): () => Unit =
    var cancelled = false
    ref()

    // Re-arm runs from inside a fired timer (loop thread), but the *first*
    // arm may come from any caller thread — defer the initial enqueue via
    // the microtask queue for the same reason as setTimeout.
    def schedule(): Unit =
      if !cancelled then
        timers.enqueue(TimerEntry(0, System.currentTimeMillis() + intervalMs, () => {
          if !cancelled then
            callback()
            schedule()
        }))

    microtasks.add(() => schedule())
    selector.wakeup()
    () =>
      if !cancelled then
        cancelled = true
        unref()

  def register(channel: SelectableChannel, ops: Int, attachment: Any = null): SelectionKey =
    channel.configureBlocking(false)
    val key = channel.register(selector, ops, attachment)
    // If the loop's daemon thread is blocked in `select()` we need to wake it
    // so it picks up the newly-registered key. Cheap when we're already on the
    // loop thread (selector.wakeup is idempotent / no-op there).
    selector.wakeup()
    key

  /** Run a callback inside the loop, swallowing any non-fatal throwable so a
    * single bad handler doesn't kill the loop daemon. Without this, an
    * uncaught exception in a Future continuation, a timer fire, or an I/O
    * handler tears the daemon down silently — every subsequent test then
    * hangs because nothing pumps microtasks anymore. We log to stderr so
    * the failure is at least visible.
    */
  private def safeRun(callback: () => Unit): Unit =
    try callback()
    catch
      case fatal if !scala.util.control.NonFatal(fatal) => throw fatal
      case e: Throwable => e.printStackTrace()

  private def drainMicrotasks(): Unit =
    var task = microtasks.poll()
    while task != null do
      safeRun(task)
      task = microtasks.poll()

  def run(): Unit =
    running = true

    try
      while running do
        drainMicrotasks()

        if _refCount.get() <= 0 && microtasks.isEmpty && immediates.isEmpty then
          running = false
        else
          val timeout =
            if microtasks.peek() != null || immediates.peek() != null then 0L
            else if timers.isEmpty then 3000L
            else
              val nearest = timers.head.deadline
              val now = System.currentTimeMillis()
              math.max(0L, nearest - now)

          if timeout == 0L then selector.selectNow()
          else selector.select(timeout)

          val now = System.currentTimeMillis()
          while timers.nonEmpty && timers.head.deadline <= now do
            val entry = timers.dequeue()
            safeRun(entry.callback)
            drainMicrotasks()

          val keys = selector.selectedKeys().iterator()
          while keys.hasNext do
            val key = keys.next()
            keys.remove()

            if key.isValid then
              val handler = key.attachment().asInstanceOf[SelectionKeyHandler]
              if handler != null then
                safeRun(() => handler.handle(key))
                drainMicrotasks()

          var imm = immediates.poll()
          while imm != null do
            safeRun(imm)
            drainMicrotasks()
            imm = immediates.poll()
      end while
    catch
      case t: Throwable =>
        // Defensive: if anything inside the loop throws (unwrapped) it would
        // silently kill the daemon — every microserve op then hangs because
        // nothing pumps microtasks. safeRun above wraps every callback; this
        // outer net catches anything missed (e.g. a bug in the loop itself).
        Console.err.println(s"[microserve] event loop crashed: ${t.getMessage}")
        t.printStackTrace()
        throw t

  def stop(): Unit =
    running = false
    selector.wakeup()
end EventLoop

private[microserve] trait SelectionKeyHandler:
  def handle(key: SelectionKey): Unit
