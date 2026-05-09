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

  /** Parse `<headers>\r\n\r\n<body>`. The body interpretation depends on the
    * `Transfer-Encoding` header:
    *
    *   - `chunked`: decode RFC 7230 §4.1 chunks into a flat byte buffer, stop
    *     at the zero-chunk terminator (`0\r\n\r\n`).
    *   - anything else (including absent): treat the rest of the buffer as
    *     the body. Works because the test client always sends
    *     `Connection: close`, so the server emits everything and hangs up.
    */
  private def parse(bytes: Array[Byte]): TestResponse =
    val sep = indexOf(bytes, "\r\n\r\n".getBytes("ISO-8859-1"))
    require(sep >= 0, "incomplete response (no header/body separator)")
    val headerStr = new String(bytes, 0, sep, "ISO-8859-1")
    val rawBody = java.util.Arrays.copyOfRange(bytes, sep + 4, bytes.length)
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

    val bodyBytes =
      if headers.get("Transfer-Encoding").exists(_.equalsIgnoreCase("chunked"))
      then decodeChunked(rawBody)
      else rawBody

    TestResponse(statusCode, statusMessage, headers, bodyBytes)

  /** Decode an RFC 7230 §4.1 chunked body. Each chunk is `<hex>\r\n<data>\r\n`
    * and the body ends at a zero-length chunk (`0\r\n\r\n` — optional trailers
    * not supported). Anything malformed throws.
    */
  private def decodeChunked(buf: Array[Byte]): Array[Byte] =
    val out = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var i = 0
    while i < buf.length do
      val crlf = indexOf(buf, "\r\n".getBytes("ISO-8859-1"), i)
      require(crlf >= 0, "chunked: no CRLF after size")
      val sizeStr = new String(buf, i, crlf - i, "ISO-8859-1").nn.trim.nn
      // Size line may have chunk-extensions after `;` — strip them.
      val sizeOnly = sizeStr.indexOf(';') match
        case -1 => sizeStr
        case k  => sizeStr.substring(0, k).nn.trim.nn
      val size = Integer.parseInt(sizeOnly, 16)
      i = crlf + 2
      if size == 0 then
        // Trailers (if any) until \r\n\r\n; we ignore them.
        return out.toArray
      val end = i + size
      require(end <= buf.length, "chunked: data shorter than declared size")
      var k = i
      while k < end do
        out += buf(k)
        k += 1
      i = end
      // Skip CRLF that follows the data.
      require(i + 1 < buf.length && buf(i) == '\r'.toByte && buf(i + 1) == '\n'.toByte, "chunked: missing CRLF after data")
      i += 2
    out.toArray

  /** Indexed substring search starting at `from`. */
  private def indexOf(haystack: Array[Byte], needle: Array[Byte], from: Int): Int =
    var i = from
    while i <= haystack.length - needle.length do
      var j = 0
      while j < needle.length && haystack(i + j) == needle(j) do j += 1
      if j == needle.length then return i
      i += 1
    -1

  /** Convenience: search from start. */
  private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
    indexOf(haystack, needle, 0)
end HttpTestClient
