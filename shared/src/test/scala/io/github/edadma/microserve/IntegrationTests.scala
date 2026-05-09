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

  // -- streaming / chunked transfer encoding --------------------------------
  // These exercise the new `Response.write(chunk)` + `Response.end()` path.

  "streaming response uses chunked transfer encoding" in {
    withServer(
      { (_, res) =>
        res.writeHead(200, Map("Content-Type" -> "text/plain"))
        res.write("hello ")
        res.write("world")
        res.end()
      },
      basePort + 11,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("Transfer-Encoding") shouldBe Some("chunked")
        resp.headers.get("Content-Length") shouldBe None
        resp.bodyString shouldBe "hello world"
      }
    }
  }

  "many small chunks reassemble correctly" in {
    val n = 50
    withServer(
      { (_, res) =>
        res.writeHead(200)
        var i = 0
        while i < n do
          res.write(s"chunk-$i\n")
          i += 1
        res.end()
      },
      basePort + 12,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("Transfer-Encoding") shouldBe Some("chunked")
        val expected = (0 until n).map(i => s"chunk-$i\n").mkString
        resp.bodyString shouldBe expected
      }
    }
  }

  "end(body) in streaming mode emits final chunk + terminator" in {
    withServer(
      { (_, res) =>
        res.writeHead(200)
        res.write("part1 ")
        res.end("part2".getBytes("UTF-8"))
      },
      basePort + 13,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("Transfer-Encoding") shouldBe Some("chunked")
        resp.bodyString shouldBe "part1 part2"
      }
    }
  }

  "SSE-style event stream with proper content type" in {
    withServer(
      { (_, res) =>
        res.writeHead(200, Map(
          "Content-Type"  -> "text/event-stream",
          "Cache-Control" -> "no-cache",
        ))
        res.write("event: greet\ndata: hello\n\n")
        res.write("event: greet\ndata: world\n\n")
        res.end()
      },
      basePort + 14,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.headers.get("Content-Type") shouldBe Some("text/event-stream")
        resp.headers.get("Cache-Control") shouldBe Some("no-cache")
        resp.headers.get("Transfer-Encoding") shouldBe Some("chunked")
        resp.bodyString shouldBe
          "event: greet\ndata: hello\n\nevent: greet\ndata: world\n\n"
      }
    }
  }

  "empty chunks are dropped (no premature termination)" in {
    withServer(
      { (_, res) =>
        res.writeHead(200)
        res.write("a")
        res.write(Array.empty[Byte]) // must not emit a terminating zero-chunk
        res.write("b")
        res.end()
      },
      basePort + 15,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.bodyString shouldBe "ab"
      }
    }
  }

  "double end is ignored in streaming mode" in {
    withServer(
      { (_, res) =>
        res.writeHead(200)
        res.write("only")
        res.end()
        res.end()              // no-op
        res.write("ignored")   // no-op
      },
      basePort + 16,
    ) { port =>
      HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
        resp.statusCode shouldBe 200
        resp.bodyString shouldBe "only"
      }
    }
  }

  // -- listen onError --------------------------------------------------------
  // Verifies the new bind-error reporting: two servers contending for the
  // same port — the second's `onError` must fire, and `onListening` must not.

  "second listen on a busy port surfaces an error via onError" in {
    val port = basePort + 17
    val firstReady = Promise[Unit]()
    val first = createServer { (_, res) => res.send("first") }
    first.listen(port, "127.0.0.1") { () => firstReady.success(()) }

    firstReady.future.flatMap { _ =>
      val errPromise = Promise[Throwable]()
      val unexpectedListening = Promise[Unit]()
      val second = createServer { (_, res) => res.send("second") }
      second.listen(port, "127.0.0.1")(
        onListening = () => unexpectedListening.trySuccess(()),
        onError     = e => errPromise.trySuccess(e),
      )

      // Whichever fires first wins. We expect onError. Cap the wait so a hung
      // platform doesn't leave the test running forever.
      val timers = summon[Runtime].timers
      val timeout = Promise[Throwable]()
      val _ = timers.setTimeout(3000)(() =>
        timeout.trySuccess(new RuntimeException("onError did not fire within 3s")),
      )

      val race = Future.firstCompletedOf(Seq(
        errPromise.future.map(Right(_)),
        unexpectedListening.future.map(_ => Left("onListening fired despite busy port")),
        timeout.future.map(t => Left(t.getMessage)),
      ))

      race.transformWith { result =>
        val drained = Promise[Unit]()
        first.close(() => drained.success(()))
        second.close()
        drained.future.transform { _ =>
          result.map {
            case Right(_) => succeed
            case Left(msg) => fail(msg)
          }
        }
      }
    }
  }

  // -- Response.onClose ------------------------------------------------------
  // Verifies the per-response close hook fires when the underlying TCP
  // connection drops mid-stream. The client opens a raw connection, reads
  // the first chunk to know the handler is live, then closes — the handler
  // should observe the close via the registered callback within a few
  // hundred ms.

  "Response.onClose fires when the client disconnects mid-stream" in {
    val port = basePort + 18
    val closeObserved = Promise[Unit]()
    val firstChunkSent = Promise[Unit]()

    val server = createServer { (_, res) =>
      res.onClose(() => closeObserved.trySuccess(()))
      res.writeHead(200, Map("Content-Type" -> "text/event-stream"))
      res.write("data: hello\n\n").map(_ => firstChunkSent.trySuccess(()))
    }

    val listening = Promise[Unit]()
    server.listen(port, "127.0.0.1") { () => listening.success(()) }

    listening.future.flatMap { _ =>
      val runtime = summon[Runtime]
      runtime.connect("127.0.0.1", port).flatMap { conn =>
        // Accumulate response bytes; once we see the chunk's hex header we
        // know the stream is live and it's safe to drop the connection to
        // exercise onClose.
        val received = scala.collection.mutable.ArrayBuffer.empty[Byte]
        val gotBody = Promise[Unit]()
        conn.onRead { chunk =>
          received ++= chunk
          // "data: " arrives once headers + first chunk frame have flushed.
          if !gotBody.isCompleted &&
             new String(received.toArray, "ISO-8859-1").contains("data: hello")
          then gotBody.success(())
        }
        conn.onClose(() => ())

        val req = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes("ISO-8859-1")
        val _ = conn.write(req)

        gotBody.future.flatMap { _ =>
          // Drop the client side of the TCP connection — the server should
          // observe this via its transport's onClose, which in turn fires
          // our Response.onClose.
          conn.close()

          val timers = runtime.timers
          val timeout = Promise[Boolean]()
          val _ = timers.setTimeout(3000)(() => timeout.trySuccess(false))
          val raceResult = Future.firstCompletedOf(Seq(
            closeObserved.future.map(_ => true),
            timeout.future,
          ))

          raceResult.transformWith { res =>
            val drained = Promise[Unit]()
            server.close(() => drained.success(()))
            drained.future.transform { _ =>
              res.map {
                case true  => succeed
                case false => fail("Response.onClose did not fire within 3s")
              }
            }
          }
        }
      }
    }
  }
end IntegrationTests
