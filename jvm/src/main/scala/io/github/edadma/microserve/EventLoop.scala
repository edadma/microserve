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
    val id = { nextTimerId += 1; nextTimerId }
    ref()
    val entry = TimerEntry(id, System.currentTimeMillis() + delayMs, () => {
      if !cancelled then
        cancelled = true
        unref()
        callback()
    })
    timers.enqueue(entry)
    selector.wakeup()
    () =>
      if !cancelled then
        cancelled = true
        unref()

  def setInterval(intervalMs: Long)(callback: () => Unit): () => Unit =
    var cancelled = false
    ref()

    def schedule(): Unit =
      if !cancelled then
        timers.enqueue(TimerEntry(0, System.currentTimeMillis() + intervalMs, () => {
          if !cancelled then
            callback()
            schedule()
        }))

    schedule()
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

  private def drainMicrotasks(): Unit =
    var task = microtasks.poll()
    while task != null do
      task()
      task = microtasks.poll()

  def run(): Unit =
    running = true

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
          entry.callback()
          drainMicrotasks()

        val keys = selector.selectedKeys().iterator()
        while keys.hasNext do
          val key = keys.next()
          keys.remove()

          if key.isValid then
            val handler = key.attachment().asInstanceOf[SelectionKeyHandler]
            if handler != null then
              handler.handle(key)
              drainMicrotasks()

        var imm = immediates.poll()
        while imm != null do
          imm()
          drainMicrotasks()
          imm = immediates.poll()
    end while

  def stop(): Unit =
    running = false
    selector.wakeup()
end EventLoop

private[microserve] trait SelectionKeyHandler:
  def handle(key: SelectionKey): Unit
