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
  // -- streaming + concurrent request interaction ----------------------------
  // Reproduces the wedge that surfaced from juicer's `serve --live-reload`
  // experience: with a long-lived SSE connection open, the next plain
  // request to the SAME server should still respond promptly. A real bug
  // here makes the second request wait ~30 seconds (the idle-timeout
  // length) before the server picks it up. We give it a generous 2s
  // budget so the assertion failure blames the bug, not test slowness.

  "plain request stays fast while a long-lived SSE stream is open" in {
    val port = basePort + 19
    val sseChunkSent = Promise[Unit]()

    val server = createServer { (req, res) =>
      if req.path == "/sse" then
        res.writeHead(200, Map(
          "Content-Type"  -> "text/event-stream",
          "Cache-Control" -> "no-cache",
        ))
        // Flush once so the client knows the stream is live; then leave
        // the response open. No `end()` — this is a deliberate forever-open.
        res.write("retry: 1000\n\n").map(_ => sseChunkSent.trySuccess(()))
      else
        res.send("hello")
    }

    val listening = Promise[Unit]()
    server.listen(port, "127.0.0.1") { () => listening.success(()) }

    listening.future.flatMap { _ =>
      val runtime = summon[Runtime]
      val timers  = runtime.timers

      // Open the SSE connection on connection A. We hold the conn open
      // (no close()) so the server keeps streaming until test cleanup.
      runtime.connect("127.0.0.1", port).flatMap { sseConn =>
        sseConn.onRead(_ => ())
        sseConn.onClose(() => ())
        val sseReq = "GET /sse HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes("ISO-8859-1")
        val _ = sseConn.write(sseReq)

        sseChunkSent.future.flatMap { _ =>
          // SSE is live — now fire a plain GET on a separate connection
          // and time it. The standard HttpTestClient uses Connection:
          // close, so the server should respond once and hang up.
          val started = System.currentTimeMillis()
          val plainResult =
            HttpTestClient.request("127.0.0.1", port, "GET", "/").map { resp =>
              val elapsed = System.currentTimeMillis() - started
              (resp, elapsed)
            }

          // Cap with a 5s timeout so a true wedge fails the test instead
          // of hanging the whole suite.
          val timeout = Promise[(TestResponse, Long)]()
          val _ = timers.setTimeout(5000)(() =>
            timeout.tryFailure(new RuntimeException("plain GET did not return within 5s — bug reproduced")),
          )

          val raceResult = Future.firstCompletedOf(Seq(plainResult, timeout.future))

          raceResult.transformWith { res =>
            sseConn.close()
            val drained = Promise[Unit]()
            server.close(() => drained.success(()))
            drained.future.transform { _ =>
              res.map { case (resp, elapsed) =>
                resp.statusCode shouldBe 200
                resp.bodyString shouldBe "hello"
                // The actual wire-level work for /hello is sub-millisecond;
                // anything beyond ~1s indicates the wedge.
                withClue(s"plain GET took ${elapsed}ms while SSE was open: ") {
                  elapsed should be < 1000L
                }
              }
            }
          }
        }
      }
    }
  }

  // Tighter reproduction: simulates a user clicking through ~6 pages in
  // rapid succession with juicer's serve --live-reload running. Each
  // "navigation" overlaps an old SSE close with a new SSE open and a plain
  // HTML fetch — the same pattern the browser produces. Reported symptom:
  // one of the plain HTML fetches takes ~30s before responding.

  "plain requests stay fast through six rapid SSE close/open cycles" in {
    val port = basePort + 20

    val server = createServer { (req, res) =>
      if req.path == "/sse" then
        res.writeHead(200, Map(
          "Content-Type"  -> "text/event-stream",
          "Cache-Control" -> "no-cache",
        ))
        // Forever-open: write once and return; never call end().
        res.write("retry: 1000\n\n")
      else
        res.send("hello")
    }

    val listening = Promise[Unit]()
    server.listen(port, "127.0.0.1") { () => listening.success(()) }

    listening.future.flatMap { _ =>
      val runtime = summon[Runtime]
      val timers  = runtime.timers

      // Open an SSE connection and wait until the first chunk arrives so
      // we KNOW the stream is live. Returns the open ConnectionTransport
      // for the caller to close when ready.
      def openSse(): Future[ConnectionTransport] = {
        val ready = Promise[Unit]()
        runtime.connect("127.0.0.1", port).flatMap { conn =>
          val received = scala.collection.mutable.ArrayBuffer.empty[Byte]
          conn.onRead { chunk =>
            received ++= chunk
            if !ready.isCompleted &&
               new String(received.toArray, "ISO-8859-1").contains("retry: 1000")
            then ready.success(())
          }
          conn.onClose(() => ())
          val _ = conn.write("GET /sse HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes("ISO-8859-1"))
          ready.future.map(_ => conn)
        }
      }

      // Loop body: simulate one "navigation" — fetch a plain HTML page,
      // open the new page's SSE, close the previous page's SSE. Returns
      // the new SSE connection plus the elapsed time of the plain fetch.
      def navigateOnce(prevSse: ConnectionTransport): Future[(ConnectionTransport, Long)] = {
        val started = System.currentTimeMillis()
        HttpTestClient.request("127.0.0.1", port, "GET", "/").flatMap { resp =>
          val elapsed = System.currentTimeMillis() - started
          if resp.statusCode != 200 then
            Future.failed(new RuntimeException(s"plain GET returned ${resp.statusCode}"))
          else
            openSse().map { newSse =>
              prevSse.close()
              (newSse, elapsed)
            }
        }
      }

      // Initial SSE (the "first page" is loaded). Then 6 navigations
      // in sequence. Track every plain-GET timing so a regression
      // points to the slow one.
      val results = scala.collection.mutable.ArrayBuffer.empty[Long]
      val start = openSse().flatMap { initialSse =>
        // Sequential fold: each navigation waits for the previous to
        // finish. This mirrors how the browser issues clicks one at a
        // time but with overlapping connection lifecycles.
        (1 to 6).foldLeft(Future.successful(initialSse)) { (acc, _) =>
          acc.flatMap { sse =>
            navigateOnce(sse).map { case (newSse, elapsed) =>
              results += elapsed
              newSse
            }
          }
        }
      }

      // Cap with a 15s timeout so a wedge fails the test instead of
      // hanging — 6 navigations × 30s wedge = ~180s without the cap.
      val timeout = Promise[ConnectionTransport]()
      val _ = timers.setTimeout(15000)(() =>
        timeout.tryFailure(new RuntimeException(
          s"navigation sequence did not complete within 15s — wedge reproduced. Timings so far: ${results.mkString(", ")}ms"
        )),
      )

      val raceResult = Future.firstCompletedOf(Seq(start, timeout.future))

      raceResult.transformWith { res =>
        // Best-effort cleanup of whatever SSE connection is still open.
        res.foreach(_.close())
        val drained = Promise[Unit]()
        server.close(() => drained.success(()))
        drained.future.transform { _ =>
          res.map { _ =>
            withClue(s"per-navigation timings: ${results.mkString(", ")}ms — ") {
              results.length shouldBe 6
              all(results) should be < 1000L
            }
          }
        }
      }
    }
  }

end IntegrationTests
