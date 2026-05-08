package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/** Tiny cross-platform HTTP/1.0-ish client used only by tests. Sends a raw
  * request, accumulates bytes via the same [[ConnectionTransport]] every
  * platform implements, and parses the response into a typed value once the
  * connection closes (or `Content-Length` worth of body has arrived).
  *
  * No dependency on `java.net.http` or any other JVM-only library so the same
  * tests run on JS and Native.
  */
final case class TestResponse(statusCode: Int, statusMessage: String, headers: Map[String, String], body: Array[Byte]):
  def bodyString: String = new String(body, "UTF-8")

object HttpTestClient:
  /** Send a raw HTTP request and return the parsed response. Sends
    * `Connection: close` so the server hangs up after one response, which
    * makes "until-EOF body read" work on every platform.
    */
  def request(
      host: String,
      port: Int,
      method: String,
      path: String,
      headers: Map[String, String] = Map.empty,
      body: Array[Byte] = Array.empty,
  )(using runtime: Runtime, ec: ExecutionContext): Future[TestResponse] =
    val responsePromise = Promise[TestResponse]()
    val received = scala.collection.mutable.ArrayBuffer.empty[Byte]

    runtime.connect(host, port).onComplete {
      case scala.util.Failure(e) => responsePromise.failure(e)
      case scala.util.Success(conn) =>
        conn.onRead { chunk => received ++= chunk }
        conn.onClose { () =>
          if !responsePromise.isCompleted then
            try responsePromise.success(parse(received.toArray))
            catch case e: Exception => responsePromise.failure(e)
        }

        val sb = new StringBuilder
        sb ++= s"$method $path HTTP/1.1\r\n"
        sb ++= s"Host: $host\r\n"
        sb ++= "Connection: close\r\n"
        if body.nonEmpty then sb ++= s"Content-Length: ${body.length}\r\n"
        headers.foreach((k, v) => sb ++= s"$k: $v\r\n")
        sb ++= "\r\n"
        val headerBytes = sb.toString.getBytes("ISO-8859-1")

        val out =
          if body.isEmpty then headerBytes
          else
            val a = new Array[Byte](headerBytes.length + body.length)
            System.arraycopy(headerBytes, 0, a, 0, headerBytes.length)
            System.arraycopy(body, 0, a, headerBytes.length, body.length)
            a

        conn.write(out).failed.foreach { e =>
          if !responsePromise.isCompleted then responsePromise.failure(e)
        }
    }

    responsePromise.future
  end request

  /** Parse `<headers>\r\n\r\n<body>`. Body length is whatever bytes follow the
    * blank line — works because we always send `Connection: close`, so the
    * server emits its full body and shuts down. No support for chunked
    * transfer-encoding (microserve never emits it).
    */
  private def parse(bytes: Array[Byte]): TestResponse =
    val sep = indexOf(bytes, "\r\n\r\n".getBytes("ISO-8859-1"))
    require(sep >= 0, "incomplete response (no header/body separator)")
    val headerStr = new String(bytes, 0, sep, "ISO-8859-1")
    val bodyBytes = java.util.Arrays.copyOfRange(bytes, sep + 4, bytes.length)
    val lines = headerStr.split("\r\n").nn

    val statusLine = lines(0).nn
    val parts = statusLine.split(" ", 3).nn
    require(parts.length >= 3, s"bad status line: $statusLine")
    val statusCode = parts(1).nn.toInt
    val statusMessage = parts(2).nn

    val headers = lines.tail.flatMap { line =>
      val l = line.nn
      val idx = l.indexOf(": ")
      if idx <= 0 then None
      else Some(l.substring(0, idx).nn -> l.substring(idx + 2).nn)
    }.toMap

    TestResponse(statusCode, statusMessage, headers, bodyBytes)

  /** Naive substring search over a byte array. We only need it to find
    * `\r\n\r\n` once per response, so a Boyer–Moore is overkill.
    */
  private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
    var i = 0
    while i <= haystack.length - needle.length do
      var j = 0
      while j < needle.length && haystack(i + j) == needle(j) do j += 1
      if j == needle.length then return i
      i += 1
    -1
end HttpTestClient
