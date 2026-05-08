package io.github.edadma.microserve

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/** Cross-platform integration tests. The same source compiles and runs on
  * JVM, Scala.js (Node), and Scala Native — each picks up its own platform
  * `given Runtime` and `HttpTestClient` drives the server through that.
  *
  * Why `AsyncFreeSpec`? On JS and Native there is no separate "server thread"
  * — everything runs on the platform's single event loop. A blocking
  * `Await.result` would deadlock that loop. Async style returns a `Future`
  * directly to ScalaTest, which integrates cleanly with the platform loop.
  *
  * Why one ephemeral port per test? Spreading tests across distinct ports
  * keeps them order-independent and lets us use a fixed port on Native (whose
  * spritzsn libuv wrapper doesn't yet expose `getsockname` for ephemeral-port
  * lookup; see [[LibuvServerTransport]]). We pick high ports unlikely to
  * collide on dev machines.
  */
class IntegrationTests extends AsyncFreeSpec with Matchers:

  given ExecutionContext = summon[Runtime].executionContext

  override def executionContext: ExecutionContext = summon[Runtime].executionContext

  /** Bind a server on `port`, run `test(port)`, then drain the server.
    * The test's `Future` resolves before we close — `.transformWith` ensures
    * cleanup runs on success and on failure.
    */
  private def withServer(handler: RequestHandler, port: Int)(test: Int => Future[org.scalatest.Assertion]): Future[org.scalatest.Assertion] =
    val listening = Promise[Unit]()
    val server = createServer(handler)
    server.listen(port, "127.0.0.1") { () => listening.success(()) }

    listening.future.flatMap(_ => test(port)).transformWith { result =>
      val drained = Promise[Unit]()
      server.close(() => drained.success(()))
      drained.future.transform(_ => result)
    }

  // Use distinct ports per test so any platform that doesn't release
  // immediately on close (TIME_WAIT etc.) doesn't make the next test flaky.
  private val basePort = 38600

  "basic GET returns 200" in {
    withServer({ (_, res) => res.send("hello") }, basePort + 0) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.bodyString shouldBe "hello"
      }
    }
  }

  "404 path returns the body the handler wrote" in {
    withServer(
      { (req, res) =>
        req.path match
          case "/" => res.send("home")
          case _   => res.status(404).send("not found")
      },
      basePort + 1,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/missing").map { resp =>
        resp.statusCode shouldBe 404
        resp.bodyString shouldBe "not found"
      }
    }
  }

  "JSON content type is set automatically" in {
    withServer({ (_, res) => res.sendJson("""{"ok":true}""") }, basePort + 2) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("Content-Type") shouldBe Some("application/json; charset=UTF-8")
        resp.bodyString shouldBe """{"ok":true}"""
      }
    }
  }

  "POST body is received and visible to the handler" in {
    withServer({ (req, res) => res.send(s"got: ${req.bodyString}") }, basePort + 3) { port =>
      HttpTestClient
        .request("127.0.0.1", port, "POST", "/", body = "test body".getBytes("UTF-8"))
        .map { resp =>
          resp.statusCode shouldBe 200
          resp.bodyString shouldBe "got: test body"
        }
    }
  }

  "query string is parsed" in {
    withServer(
      { (req, res) =>
        val q = req.query.getOrElse("q", "")
        val page = req.query.getOrElse("page", "")
        res.send(s"q=$q page=$page")
      },
      basePort + 4,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/search?q=hello&page=2").map { resp =>
        resp.statusCode shouldBe 200
        resp.bodyString shouldBe "q=hello page=2"
      }
    }
  }

  "request headers are case-insensitive" in {
    withServer(
      { (req, res) =>
        val v1 = req.get("X-Custom").getOrElse("")
        val v2 = req.get("x-custom").getOrElse("")
        res.send(s"v1=$v1 v2=$v2")
      },
      basePort + 5,
    ) { port =>
      HttpTestClient
        .request("127.0.0.1", port, "GET", "/", headers = Map("X-Custom" -> "value"))
        .map { resp =>
          resp.statusCode shouldBe 200
          resp.bodyString shouldBe "v1=value v2=value"
        }
    }
  }

  "custom response headers are emitted" in {
    withServer(
      { (_, res) =>
        res.set("X-Request-Id", "abc123")
        res.send("ok")
      },
      basePort + 6,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("X-Request-Id") shouldBe Some("abc123")
      }
    }
  }

  "double send is ignored (only first body wins)" in {
    withServer(
      { (_, res) =>
        res.send("first")
        res.send("second")
      },
      basePort + 7,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.bodyString shouldBe "first"
      }
    }
  }

  "synchronous handler exception becomes a 500" in {
    withServer({ (_, _) => throw new RuntimeException("sync boom") }, basePort + 8) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 500
        resp.bodyString should include("sync boom")
      }
    }
  }

  "async handler failure becomes a 500" in {
    withServer({ (_, _) => Future.failed(new RuntimeException("async boom")) }, basePort + 9) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 500
        resp.bodyString should include("async boom")
      }
    }
  }

  "large body round-trips" in {
    val big = "x" * 20000
    withServer({ (req, res) => res.send(s"size=${req.body.length}") }, basePort + 10) { port =>
      HttpTestClient
        .request("127.0.0.1", port, "POST", "/", body = big.getBytes("UTF-8"))
        .map { resp =>
          resp.statusCode shouldBe 200
          resp.bodyString shouldBe "size=20000"
        }
    }
  }
end IntegrationTests
