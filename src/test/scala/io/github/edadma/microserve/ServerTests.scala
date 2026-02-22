package io.github.edadma.microserve

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.net.{Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
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
