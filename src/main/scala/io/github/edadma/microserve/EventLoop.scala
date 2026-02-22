package io.github.edadma.microserve

import java.nio.channels.{Selector, SelectionKey, SelectableChannel}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.PriorityQueue
import scala.concurrent.ExecutionContext

case class TimerEntry(id: Long, deadline: Long, callback: () => Unit)

class EventLoop:
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

  /** Schedule a callback to run as a microtask (highest priority). */
  def nextTick(callback: () => Unit): Unit =
    microtasks.add(callback)
    selector.wakeup()

  /** Schedule a callback to run after I/O poll (lower priority than microtasks). */
  def setImmediate(callback: () => Unit): Unit =
    immediates.add(callback)
    selector.wakeup()

  /** Schedule a callback after `delayMs` milliseconds. Returns a cancel function. */
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

  /** Schedule a repeating callback every `intervalMs` milliseconds. Returns a cancel function. */
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

  /** Register a channel with this event loop's selector. */
  def register(channel: SelectableChannel, ops: Int, attachment: Any = null): SelectionKey =
    channel.configureBlocking(false)
    channel.register(selector, ops, attachment)

  /** Drain all pending microtasks (including ones enqueued during draining). */
  private def drainMicrotasks(): Unit =
    var task = microtasks.poll()
    while task != null do
      task()
      task = microtasks.poll()

  /** Run the event loop until there's no more work to do. */
  def run(): Unit =
    running = true

    while running do
      // 1. Drain microtasks
      drainMicrotasks()

      // 2. Check continue condition
      if _refCount.get() <= 0 && microtasks.isEmpty && immediates.isEmpty then
        running = false
      else
        // 3. Calculate timeout
        val timeout =
          if microtasks.peek() != null || immediates.peek() != null then 0L
          else if timers.isEmpty then 3000L
          else
            val nearest = timers.head.deadline
            val now = System.currentTimeMillis()
            math.max(0L, nearest - now)

        // 4. Block on selector
        if timeout == 0L then selector.selectNow()
        else selector.select(timeout)

        // 5. Process expired timers — each callback + drainMicrotasks
        val now = System.currentTimeMillis()
        while timers.nonEmpty && timers.head.deadline <= now do
          val entry = timers.dequeue()
          entry.callback()
          drainMicrotasks()

        // 6. Process I/O events — each handler + drainMicrotasks
        val keys = selector.selectedKeys().iterator()
        while keys.hasNext do
          val key = keys.next()
          keys.remove()

          if key.isValid then
            val handler = key.attachment().asInstanceOf[SelectionKeyHandler]
            if handler != null then
              handler.handle(key)
              drainMicrotasks()

        // 7. Process immediates — each callback + drainMicrotasks
        var imm = immediates.poll()
        while imm != null do
          imm()
          drainMicrotasks()
          imm = immediates.poll()
    end while

  def stop(): Unit =
    running = false
    selector.wakeup()

/** Trait for handling selector key events. */
trait SelectionKeyHandler:
  def handle(key: SelectionKey): Unit
