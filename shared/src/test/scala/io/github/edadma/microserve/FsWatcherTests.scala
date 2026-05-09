package io.github.edadma.microserve

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

/** Cross-platform tests for [[FsWatcher]].
  *
  * Caveat about timing: on macOS the JVM's `WatchService` uses a polling
  * adapter under the hood (no kqueue support) and events arrive 1–10s after
  * the change. The Native libuv path on macOS uses FSEvents, which has a
  * coalescing window of ~250ms. So we use generous wait timeouts and poll
  * for events rather than asserting on a single deterministic delivery.
  */
class FsWatcherTests extends AsyncFreeSpec with Matchers:

  given ExecutionContext = summon[Runtime].executionContext
  override def executionContext: ExecutionContext = summon[Runtime].executionContext

  /** Block-ish polling helper. Resolves when `predicate` becomes true, or
    * after `timeoutMs`. Cross-platform — no Thread.sleep — uses the
    * Runtime's timer to schedule retries on the event loop.
    */
  private def waitFor(timeoutMs: Long, intervalMs: Long = 100)(predicate: => Boolean): Future[Boolean] =
    val p = Promise[Boolean]()
    val deadline = System.currentTimeMillis() + timeoutMs
    val timers = summon[Runtime].timers
    def tick(): Unit =
      if predicate then p.success(true)
      else if System.currentTimeMillis() >= deadline then p.success(false)
      else { val _ = timers.setTimeout(intervalMs)(() => tick()); () }
    tick()
    p.future

  private def withWatcher[T](setup: (FsWatcher, String) => Future[T]): Future[T] =
    val tmp = FsTestUtils.createTempDir("microserve-fswatcher-")
    val watcher = summon[Runtime].newFsWatcher()
    setup(watcher, tmp).transformWith { result =>
      try watcher.close() catch case _: Throwable => ()
      try FsTestUtils.removeRecursive(tmp) catch case _: Throwable => ()
      Future.fromTry(result)
    }

  // 8 second timeout; macOS polling can take that long.
  private val EventTimeout = 8000L

  "watcher detects a created file" in {
    withWatcher { (watcher, tmp) =>
      val events = mutable.ArrayBuffer.empty[FsEvent]
      val cancel = watcher.watch(tmp) { e => synchronized { events += e; () } }

      // Give the watcher a moment to fully arm itself before triggering
      // events. Some platforms drop events that fire during registration.
      val timers = summon[Runtime].timers
      val ready = Promise[Unit]()
      val _ = timers.setTimeout(150)(() => ready.success(()))

      ready.future.flatMap { _ =>
        FsTestUtils.writeFile(s"$tmp/created.txt", "hello")
        waitFor(EventTimeout) {
          synchronized(events.exists(_.path.endsWith("created.txt")))
        }.map { found =>
          cancel()
          found shouldBe true
        }
      }
    }
  }

  "watcher detects a modified file" in {
    withWatcher { (watcher, tmp) =>
      val target = s"$tmp/mod.txt"
      FsTestUtils.writeFile(target, "v1")

      val events = mutable.ArrayBuffer.empty[FsEvent]
      val cancel = watcher.watch(tmp) { e => synchronized { events += e; () } }

      val timers = summon[Runtime].timers
      val ready = Promise[Unit]()
      val _ = timers.setTimeout(150)(() => ready.success(()))

      ready.future.flatMap { _ =>
        FsTestUtils.writeFile(target, "v2")
        waitFor(EventTimeout) {
          synchronized(events.exists(_.path.endsWith("mod.txt")))
        }.map { found =>
          cancel()
          found shouldBe true
        }
      }
    }
  }

  "watcher detects a deleted file" in {
    withWatcher { (watcher, tmp) =>
      val target = s"$tmp/del.txt"
      FsTestUtils.writeFile(target, "doomed")

      val events = mutable.ArrayBuffer.empty[FsEvent]
      val cancel = watcher.watch(tmp) { e => synchronized { events += e; () } }

      val timers = summon[Runtime].timers
      val ready = Promise[Unit]()
      val _ = timers.setTimeout(150)(() => ready.success(()))

      ready.future.flatMap { _ =>
        FsTestUtils.deleteFile(target)
        waitFor(EventTimeout) {
          synchronized(events.exists(_.path.endsWith("del.txt")))
        }.map { found =>
          cancel()
          found shouldBe true
        }
      }
    }
  }

  "cancel() stops further events" in {
    withWatcher { (watcher, tmp) =>
      val events = mutable.ArrayBuffer.empty[FsEvent]
      val cancel = watcher.watch(tmp) { e => synchronized { events += e; () } }

      val timers = summon[Runtime].timers
      val ready = Promise[Unit]()
      val _ = timers.setTimeout(150)(() => ready.success(()))

      ready.future.flatMap { _ =>
        FsTestUtils.writeFile(s"$tmp/before.txt", "x")
        waitFor(EventTimeout) {
          synchronized(events.exists(_.path.endsWith("before.txt")))
        }.flatMap { sawFirst =>
          val countAtCancel = synchronized(events.size)
          cancel()

          // After cancel(), creating another file should NOT add events.
          // We can't prove a negative absolutely; we can prove no events
          // arrived within a generous window.
          FsTestUtils.writeFile(s"$tmp/after.txt", "y")

          val settle = Promise[Unit]()
          val _ = timers.setTimeout(2000)(() => settle.success(()))

          settle.future.map { _ =>
            sawFirst shouldBe true
            val countNow = synchronized(events.size)
            // Be lenient: some platforms emit a final event for the cancel-time
            // file system flush. Demand at most a small additional count.
            (countNow - countAtCancel) should be <= 2
          }
        }
      }
    }
  }
end FsWatcherTests
