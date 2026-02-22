package io.github.edadma.microserve

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}

class EventLoopTests extends AnyFreeSpec with Matchers:

  "nextTick fires before setTimeout(0)" in {
    val loop = new EventLoop
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setTimeout(0) { () =>
      order += "timeout"
    }
    loop.nextTick { () =>
      order += "tick"
      loop.unref()
    }

    loop.run()
    order.toSeq shouldBe Seq("tick", "timeout")
  }

  "microtasks enqueued during macrotask drain before next macrotask" in {
    val loop = new EventLoop
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setTimeout(0) { () =>
      order += "timer1"
      loop.nextTick { () =>
        order += "micro-from-timer1"
      }
    }
    loop.setTimeout(0) { () =>
      order += "timer2"
      loop.unref()
    }

    loop.run()
    order.toSeq shouldBe Seq("timer1", "micro-from-timer1", "timer2")
  }

  "setImmediate runs after I/O poll" in {
    val loop = new EventLoop
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setImmediate { () =>
      order += "immediate"
      loop.unref()
    }

    loop.run()
    order.toSeq shouldBe Seq("immediate")
  }

  "Future callbacks run via executionContext" in {
    val loop = new EventLoop
    given scala.concurrent.ExecutionContext = loop.executionContext
    var result = ""

    loop.ref()
    Future {
      result = "done"
      loop.unref()
    }

    loop.run()
    result shouldBe "done"
  }

  "loop exits when refCount drops to 0" in {
    val loop = new EventLoop
    loop.ref()
    loop.nextTick { () =>
      loop.unref()
    }
    loop.run()
    loop.refCount shouldBe 0
  }

  "setTimeout holds ref until fired" in {
    val loop = new EventLoop
    var fired = false

    loop.setTimeout(10) { () =>
      fired = true
    }

    loop.run()
    fired shouldBe true
    loop.refCount shouldBe 0
  }

  "cancelled setTimeout releases ref" in {
    val loop = new EventLoop
    var fired = false

    val cancel = loop.setTimeout(50) { () =>
      fired = true
    }
    loop.ref()
    loop.nextTick { () =>
      cancel()
      loop.unref()
    }

    loop.run()
    fired shouldBe false
    loop.refCount shouldBe 0
  }

  "nextTick fires before setImmediate" in {
    val loop = new EventLoop
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setImmediate { () =>
      order += "immediate"
      loop.unref()
    }
    loop.nextTick { () =>
      order += "tick"
    }

    loop.run()
    order.toSeq shouldBe Seq("tick", "immediate")
  }

  "nested nextTick drains fully before setTimeout(0)" in {
    val loop = new EventLoop
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setTimeout(0) { () =>
      order += "timeout"
      loop.unref()
    }
    loop.nextTick { () =>
      order += "tick1"
      loop.nextTick { () =>
        order += "tick2"
        loop.nextTick { () =>
          order += "tick3"
        }
      }
    }

    loop.run()
    order.toSeq shouldBe Seq("tick1", "tick2", "tick3", "timeout")
  }

  "setInterval fires multiple times and cancel stops it" in {
    val loop = new EventLoop
    val fires = ArrayBuffer[Int]()
    var count = 0

    val cancel = loop.setInterval(10) { () =>
      count += 1
      fires += count
    }

    loop.setTimeout(80) { () =>
      cancel()
    }

    loop.run()
    fires.size should be >= 3
    val countAtCancel = fires.size

    // Verify it actually stopped — run a bit more with a short timer
    loop.ref()
    loop.setTimeout(50) { () =>
      loop.unref()
    }
    loop.run()
    fires.size shouldBe countAtCancel
  }

  "chained Future continuations resolve between macrotasks" in {
    val loop = new EventLoop
    given scala.concurrent.ExecutionContext = loop.executionContext
    val order = ArrayBuffer[String]()

    loop.ref()
    loop.setTimeout(0) { () =>
      Future("a").map(_ + "b").map(_ + "c").foreach { result =>
        order += s"chain=$result"
      }
    }
    loop.setTimeout(0) { () =>
      order += "timer2"
      loop.unref()
    }

    loop.run()
    order.toSeq shouldBe Seq("chain=abc", "timer2")
  }

  "cancelled long timer doesn't keep loop alive" in {
    val loop = new EventLoop
    var fired = false

    val cancel = loop.setTimeout(1000) { () =>
      fired = true
    }

    loop.ref()
    loop.nextTick { () =>
      cancel()
      loop.unref()
    }

    val start = System.currentTimeMillis()
    loop.run()
    val elapsed = System.currentTimeMillis() - start

    fired shouldBe false
    elapsed should be < 500L
  }
