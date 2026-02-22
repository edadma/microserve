package io.github.edadma.microserve

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.io.{BufferedReader, InputStreamReader, InputStream, PrintWriter, OutputStream}
import java.net.{Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.CountDownLatch
import scala.concurrent.Future

trait TestHelper:
  def withServer(handler: RequestHandler)(test: (EventLoop, Server, Int) => Unit): Unit =
    val loop = new EventLoop
    val server = createServer(loop)(handler)
    var port = 0

    server.listen(0) { () =>
      port = server.actualPort
    }

    val thread = new Thread(() => loop.run())
    thread.setDaemon(true)
    thread.start()

    Thread.sleep(100)

    try test(loop, server, port)
    finally
      server.close { () => loop.stop() }
      thread.join(3000)

  def rawHttpRequest(port: Int, request: String): String =
    val socket = new Socket("localhost", port)
    try
      socket.setSoTimeout(5000)
      val out = new PrintWriter(socket.getOutputStream, true)
      out.print(request)
      out.flush()
      val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
      val sb = new StringBuilder
      var line = in.readLine()
      while line != null do
        sb.append(line).append("\n")
        line = in.readLine()
      sb.toString
    finally
      try socket.close() catch case _: Exception => ()

  /** Read a single HTTP response from a persistent socket, returning (statusLine, headers, body). */
  def readHttpResponse(in: InputStream): (String, Map[String, String], String) =
    val reader = new BufferedReader(new InputStreamReader(in))
    val statusLine = reader.readLine()
    val headers = scala.collection.mutable.Map[String, String]()
    var line = reader.readLine()
    while line != null && line.nonEmpty do
      val idx = line.indexOf(": ")
      if idx > 0 then headers(line.substring(0, idx)) = line.substring(idx + 2)
      line = reader.readLine()
    val contentLength = headers.getOrElse("Content-Length", "0").toInt
    val bodyChars = new Array[Char](contentLength)
    var read = 0
    while read < contentLength do
      val n = reader.read(bodyChars, read, contentLength - read)
      if n == -1 then read = contentLength // EOF
      else read += n
    (statusLine, headers.toMap, new String(bodyChars))

class ServerTests extends AnyFreeSpec with Matchers with TestHelper:
  private val client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  "basic GET returns 200" in {
    withServer { (req, res) =>
      res.send("hello")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "hello"
    }
  }

  "404 for unknown path" in {
    withServer { (req, res) =>
      req.path match
        case "/" => res.send("home")
        case _   => res.status(404).send("not found")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/unknown"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 404
      response.body() shouldBe "not found"
    }
  }

  "JSON response" in {
    withServer { (req, res) =>
      res.sendJson("""{"ok":true}""")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("Content-Type").orElse("") shouldBe "application/json; charset=UTF-8"
      response.body() shouldBe """{"ok":true}"""
    }
  }

  "POST with body" in {
    withServer { (req, res) =>
      res.send(s"got: ${req.bodyString}")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .POST(HttpRequest.BodyPublishers.ofString("test body"))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "got: test body"
    }
  }

  "async handler" in {
    val loop = new EventLoop
    given scala.concurrent.ExecutionContext = loop.executionContext
    val server = createServer(loop) { (req, res) =>
      Future {
        res.send("async hello")
      }.flatten
    }
    var port = 0
    server.listen(0) { () => port = server.actualPort }
    val thread = new Thread(() => loop.run())
    thread.setDaemon(true)
    thread.start()
    Thread.sleep(100)

    try
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "async hello"
    finally
      server.close { () => loop.stop() }
      thread.join(3000)
  }

  "async error returns 500" in {
    withServer { (req, res) =>
      Future.failed(new RuntimeException("async boom"))
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 500
      response.body() should include("async boom")
    }
  }

  "sync error returns 500" in {
    withServer { (req, res) =>
      throw new RuntimeException("sync boom")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 500
      response.body() should include("sync boom")
    }
  }

  "keep-alive: two requests on same client" in {
    var requestCount = 0
    withServer { (req, res) =>
      requestCount += 1
      res.send(s"req $requestCount")
    } { (_, _, port) =>
      val req1 = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val res1 = client.send(req1, HttpResponse.BodyHandlers.ofString())
      res1.statusCode() shouldBe 200
      res1.body() shouldBe "req 1"

      val req2 = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val res2 = client.send(req2, HttpResponse.BodyHandlers.ofString())
      res2.statusCode() shouldBe 200
      res2.body() shouldBe "req 2"
    }
  }

  "Connection: close header" in {
    withServer { (req, res) =>
      res.send("closing")
    } { (_, _, port) =>
      val raw = rawHttpRequest(port, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
      raw should include("HTTP/1.1 200 OK")
      raw should include("Connection: close")
    }
  }

  "graceful shutdown" in {
    withServer { (req, res) =>
      res.send("ok")
    } { (loop, server, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
    }
  }

  "graceful shutdown lets in-flight request complete" in {
    val loop = new EventLoop
    val server = createServer(loop) { (req, res) =>
      req.path match
        case "/slow" =>
          loop.setTimeout(200) { () =>
            res.send("delayed response")
          }
          Future.unit
        case _ =>
          res.send("fast")
    }
    var port = 0
    server.listen(0) { () => port = server.actualPort }
    val thread = new Thread(() => loop.run())
    thread.setDaemon(true)
    thread.start()
    Thread.sleep(100)

    try
      // Send the slow request asynchronously
      val slowFuture = java.util.concurrent.CompletableFuture.supplyAsync { () =>
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"http://localhost:$port/slow"))
          .GET()
          .build()
        client.send(req, HttpResponse.BodyHandlers.ofString())
      }

      // Give the request time to arrive at the server
      Thread.sleep(50)

      // Close the server while the request is in flight
      val drained = new CountDownLatch(1)
      server.close { () =>
        drained.countDown()
        loop.stop()
      }

      // The in-flight request should complete successfully
      val response = slowFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
      response.statusCode() shouldBe 200
      response.body() shouldBe "delayed response"

      // Server should drain after the response
      drained.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

      // New connections should be refused
      val ex = intercept[Exception] {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"http://localhost:$port/"))
          .GET()
          .build()
        client.send(req, HttpResponse.BodyHandlers.ofString())
      }
      ex should not be null
    finally
      thread.join(3000)
  }

  "multiple concurrent connections" in {
    withServer { (req, res) =>
      res.send(s"hello from ${req.path}")
    } { (_, _, port) =>
      val client1 = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
      val client2 = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

      val latch = new CountDownLatch(2)
      var res1: HttpResponse[String] = null
      var res2: HttpResponse[String] = null

      val t1 = new Thread(() => {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"http://localhost:$port/a"))
          .GET()
          .build()
        res1 = client1.send(req, HttpResponse.BodyHandlers.ofString())
        latch.countDown()
      })
      val t2 = new Thread(() => {
        val req = HttpRequest.newBuilder()
          .uri(URI.create(s"http://localhost:$port/b"))
          .GET()
          .build()
        res2 = client2.send(req, HttpResponse.BodyHandlers.ofString())
        latch.countDown()
      })

      t1.start()
      t2.start()
      latch.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

      res1.statusCode() shouldBe 200
      res1.body() shouldBe "hello from /a"
      res2.statusCode() shouldBe 200
      res2.body() shouldBe "hello from /b"
    }
  }

  "query string parsing" in {
    withServer { (req, res) =>
      val q = req.query.getOrElse("q", "")
      val page = req.query.getOrElse("page", "")
      res.send(s"q=$q page=$page")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/search?q=hello&page=2"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "q=hello page=2"
    }
  }

  "request headers accessible (case-insensitive)" in {
    withServer { (req, res) =>
      val v1 = req.get("X-Custom").getOrElse("")
      val v2 = req.get("x-custom").getOrElse("")
      res.send(s"v1=$v1 v2=$v2")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .header("X-Custom", "test-value")
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "v1=test-value v2=test-value"
    }
  }

  "custom response headers" in {
    withServer { (req, res) =>
      res.set("X-Request-Id", "abc123")
      res.send("ok")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("X-Request-Id").orElse("") shouldBe "abc123"
    }
  }

  "sendHtml sets correct content type" in {
    withServer { (req, res) =>
      res.sendHtml("<h1>Hi</h1>")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.headers().firstValue("Content-Type").orElse("") shouldBe "text/html; charset=UTF-8"
      response.body() shouldBe "<h1>Hi</h1>"
    }
  }

  "double send is ignored" in {
    withServer { (req, res) =>
      res.send("first")
      res.send("second")
    } { (_, _, port) =>
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "first"
    }
  }

  "large request body" in {
    withServer { (req, res) =>
      res.send(s"size=${req.body.length}")
    } { (_, _, port) =>
      val largeBody = "x" * 20000
      val request = HttpRequest.newBuilder()
        .uri(URI.create(s"http://localhost:$port/"))
        .POST(HttpRequest.BodyPublishers.ofString(largeBody))
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() shouldBe 200
      response.body() shouldBe "size=20000"
    }
  }

  "keep-alive with interleaved clients" in {
    var requestCount = new java.util.concurrent.atomic.AtomicInteger(0)
    withServer { (req, res) =>
      val n = requestCount.incrementAndGet()
      res.send(s"${req.path}:$n")
    } { (_, _, port) =>
      val sockA = new Socket("localhost", port)
      val sockB = new Socket("localhost", port)
      try
        sockA.setSoTimeout(5000)
        sockB.setSoTimeout(5000)

        def sendRequest(sock: Socket, path: String): (String, Map[String, String], String) =
          val out = sock.getOutputStream
          val req = s"GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n"
          out.write(req.getBytes("ASCII"))
          out.flush()
          readHttpResponse(sock.getInputStream)

        // Client A request 1
        val (statusA1, _, bodyA1) = sendRequest(sockA, "/a1")
        statusA1 should include("200")

        // Client B request 1
        val (statusB1, _, bodyB1) = sendRequest(sockB, "/b1")
        statusB1 should include("200")

        // Client A request 2 (reusing connection)
        val (statusA2, _, bodyA2) = sendRequest(sockA, "/a2")
        statusA2 should include("200")

        // Client B request 2 (reusing connection)
        val (statusB2, _, bodyB2) = sendRequest(sockB, "/b2")
        statusB2 should include("200")

        // Each client got responses for its own paths
        bodyA1 should startWith("/a1:")
        bodyB1 should startWith("/b1:")
        bodyA2 should startWith("/a2:")
        bodyB2 should startWith("/b2:")
      finally
        try sockA.close() catch case _: Exception => ()
        try sockB.close() catch case _: Exception => ()
    }
  }
