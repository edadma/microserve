package io.github.edadma.microserve

import java.nio.channels.{Selector, SelectionKey, SelectableChannel}
import scala.collection.mutable
import scala.collection.mutable.PriorityQueue

case class TimerEntry(deadline: Long, callback: () => Unit)

class EventLoop:
  val selector: Selector = Selector.open()

  private val timers = PriorityQueue.empty[TimerEntry](
    using Ordering.by[TimerEntry, Long](_.deadline).reverse,
  )

  private val callbacks = mutable.Queue[() => Unit]()

  private var running = false

  /** Schedule a callback to run on the next tick of the event loop. */
  def nextTick(callback: () => Unit): Unit =
    callbacks.enqueue(callback)
    selector.wakeup()

  /** Schedule a callback after `delayMs` milliseconds. Returns a cancel function. */
  def setTimeout(delayMs: Long)(callback: () => Unit): () => Unit =
    var cancelled = false
    val entry = TimerEntry(System.currentTimeMillis() + delayMs, () => if !cancelled then callback())
    timers.enqueue(entry)
    selector.wakeup()
    () => cancelled = true

  /** Schedule a repeating callback every `intervalMs` milliseconds. Returns a cancel function. */
  def setInterval(intervalMs: Long)(callback: () => Unit): () => Unit =
    var cancelled = false

    def schedule(): Unit =
      if !cancelled then
        timers.enqueue(TimerEntry(System.currentTimeMillis() + intervalMs, () => {
          if !cancelled then
            callback()
            schedule()
        }))

    schedule()
    () => cancelled = true

  /** Register a channel with this event loop's selector. */
  def register(channel: SelectableChannel, ops: Int, attachment: Any = null): SelectionKey =
    channel.configureBlocking(false)
    channel.register(selector, ops, attachment)

  /** Run the event loop until there's no more work to do. */
  def run(): Unit =
    running = true

    while running do
      // 1. Process pending callbacks
      while callbacks.nonEmpty do
        val cb = callbacks.dequeue()
        cb()

      // 2. Calculate timeout from nearest timer
      val timeout =
        if callbacks.nonEmpty then 0L
        else if timers.isEmpty then
          if selector.keys().isEmpty then
            running = false
            0L
          else 3000L // default poll timeout
        else
          val nearest = timers.head.deadline
          val now = System.currentTimeMillis()
          math.max(0L, nearest - now)

      if running then
        // 3. Block on selector (the heart of the event loop)
        val readyCount =
          if timeout == 0L then selector.selectNow()
          else selector.select(timeout)

        // 4. Process expired timers
        val now = System.currentTimeMillis()
        while timers.nonEmpty && timers.head.deadline <= now do
          val entry = timers.dequeue()
          entry.callback()

        // 5. Process I/O events
        if readyCount > 0 then
          val keys = selector.selectedKeys().iterator()
          while keys.hasNext do
            val key = keys.next()
            keys.remove()

            if key.isValid then
              val handler = key.attachment().asInstanceOf[SelectionKeyHandler]
              if handler != null then handler.handle(key)
    end while

  def stop(): Unit =
    running = false
    selector.wakeup()

/** Trait for handling selector key events. */
trait SelectionKeyHandler:
  def handle(key: SelectionKey): Unit
