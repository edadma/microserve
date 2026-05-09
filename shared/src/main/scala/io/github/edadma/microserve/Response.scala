package io.github.edadma.microserve

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** The platform-neutral HTTP response.
  *
  * Two modes, picked implicitly:
  *
  *   - **One-shot.** `send` / `sendJson` / `sendHtml` / `sendStatus` / `end(body)`
  *     compose status line + headers + body in one buffer with `Content-Length`,
  *     write once, and call `onFinish`. Suitable for ordinary HTTP responses.
  *
  *   - **Streaming (chunked).** Calling `write(chunk)` before any one-shot
  *     terminator switches the response to `Transfer-Encoding: chunked` —
  *     headers are flushed on first `write`, each subsequent `write` emits a
  *     proper chunk frame, and `end()` (or `end(body)` with optional final
  *     bytes) emits the terminating `0\r\n\r\n`. Suitable for SSE, NDJSON,
  *     long polling, large file streaming.
  *
  * Calling any terminator more than once is a no-op (mirrors Node.js).
  *
  * @param onStreamStart Fires the first time `write` flushes streaming
  *                      headers. ConnectionState uses this to cancel its idle
  *                      timeout for the duration of the stream — without it,
  *                      a long-running SSE connection would be killed at the
  *                      30s idle threshold despite being actively serving.
  */
class Response private[microserve] (
    private val transport: ConnectionTransport,
    private val httpVersion: String = "1.1",
    private val requestConnectionHeader: Option[String] = None,
    private val onStreamStart: () => Unit = () => (),
    private val onFinish: Boolean => Unit = _ => (),
):
  private var _statusCode: Int = 200
  private var _statusMessage: String = "OK"
  private val headers = mutable.LinkedHashMap[String, String]()
  private var headersSent = false
  private var streaming = false
  private var ended = false
  private var closeHandler: () => Unit = () => ()
  private var closeFired = false

  /** True iff a terminator has run and the response is closed to further
    * writes. Equivalent to "headers AND body are fully on the wire (or being
    * flushed)". Distinguished from `headersSent` because in streaming mode
    * headers fly long before `end()` does.
    */
  def isSent: Boolean = ended

  def status(code: Int): Response =
    _statusCode = code
    _statusMessage = HTTP.statusMessageString(code)
    this

  def set(key: String, value: Any): Response =
    headers(key) = String.valueOf(value)
    this

  /** Set status + initial headers in one call. Chainable. Does NOT flush; the
    * actual header bytes go out on the first `write` (streaming) or terminator
    * (one-shot). Mirrors Node's `res.writeHead` shape, except Node flushes
    * immediately — we flush lazily so a chained `.set(...)` still works.
    */
  def writeHead(code: Int, hdrs: Map[String, String] = Map.empty): Response =
    status(code)
    hdrs.foreach((k, v) => headers(k) = v)
    this

  /** Register a callback that fires exactly once when the underlying
    * connection closes — peer disconnect, network error, or server shutdown.
    *
    * Useful for streaming responses (SSE, NDJSON, long-poll) where the
    * server holds a long-lived `Response` reference: when the client tab
    * closes, the channel can prune the dead subscriber immediately instead
    * of discovering the disconnect on the next failed `write`.
    *
    * For one-shot responses this typically fires after `onFinish` (clean
    * keep-alive close after the response went out), so it's harmless but
    * usually uninteresting. The callback fires at most once per response;
    * setting it multiple times keeps only the last handler.
    */
  def onClose(handler: () => Unit): Unit =
    closeHandler = handler

  /** Internal — invoked by [[ConnectionState]] when the underlying transport
    * closes. Fires the user's `onClose` handler exactly once and marks the
    * response as terminated so any subsequent `write`/`end` is a no-op.
    */
  private[microserve] def fireClose(): Unit =
    if closeFired then return
    closeFired = true
    ended = true
    try closeHandler() catch case _: Throwable => ()

  // -- one-shot terminators --------------------------------------------------

  def send(data: String): Future[Unit] =
    headers.getOrElseUpdate("Content-Type", "text/plain; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendHtml(data: String): Future[Unit] =
    headers.getOrElseUpdate("Content-Type", "text/html; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendJson(data: String): Future[Unit] =
    headers.getOrElseUpdate("Content-Type", "application/json; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendStatus(code: Int): Future[Unit] =
    status(code)
    send(s"${HTTP.statusMessageString(code)}")

  // -- streaming primitives --------------------------------------------------

  /** Emit a chunk. Triggers chunked-encoding mode the first time it's called
    * (flushing headers with `Transfer-Encoding: chunked`). After `end()` it's
    * a no-op. Empty chunks are dropped — they're a valid mid-stream wire
    * value but RFC 7230 reserves zero-length for the terminator, and emitting
    * one accidentally would prematurely close the response.
    */
  def write(chunk: Array[Byte]): Future[Unit] =
    if ended then return Future.successful(())
    if !headersSent then beginStream()
    if chunk.isEmpty then return Future.successful(())
    transport.write(chunkFrame(chunk))

  def write(chunk: String): Future[Unit] = write(chunk.getBytes("UTF-8"))

  // -- terminator ------------------------------------------------------------

  /** Terminate the response.
    *
    *   - **One-shot mode** (no prior `write`): compose status + headers +
    *     `Content-Length: <body.length>` + body in one buffer.
    *   - **Streaming mode** (prior `write` happened): write `body` as a final
    *     chunk frame (if non-empty), then the zero-chunk terminator.
    *
    * Either way, `onFinish` runs synchronously with the keep-alive decision
    * after the last write is queued.
    */
  def end(body: Array[Byte] = Array.empty): Future[Unit] =
    if ended then return Future.successful(())
    ended = true

    if streaming then endStreaming(body) else endOneShot(body)
  end end

  // -- internals -------------------------------------------------------------

  /** True if this is HTTP/1.1 without `Connection: close`, or HTTP/1.0 with an
    * explicit `Connection: keep-alive`. Matches RFC 7230 §6.3.
    */
  private def shouldKeepAlive: Boolean =
    val connHeader = requestConnectionHeader.map(_.toLowerCase)
    if httpVersion == "1.1" then !connHeader.contains("close")
    else connHeader.contains("keep-alive")

  /** Flush status line + headers in chunked mode. Called from the first
    * `write`. Marks the response as streaming so subsequent writes emit
    * chunk frames and `end()` emits the zero-chunk terminator.
    */
  private def beginStream(): Unit =
    streaming = true
    headersSent = true

    headers.getOrElseUpdate("Date", httpDateNow())
    headers("Transfer-Encoding") = "chunked"
    headers.remove("Content-Length") // mutually exclusive with chunked

    val keepAlive = shouldKeepAlive
    if keepAlive then headers.getOrElseUpdate("Connection", "keep-alive")
    else headers("Connection") = "close"

    val buf = new StringBuilder
    buf ++= s"HTTP/$httpVersion ${_statusCode} ${_statusMessage}\r\n"
    headers.foreach((k, v) => buf ++= s"$k: $v\r\n")
    buf ++= "\r\n"

    transport.write(buf.toString.getBytes("ISO-8859-1"))
    onStreamStart()

  /** RFC 7230 §4.1 chunk: `<hex-size>\r\n<data>\r\n`. The hex size is the
    * data length, lowercase or uppercase — we use lowercase. No
    * chunk-extensions emitted.
    */
  private def chunkFrame(bytes: Array[Byte]): Array[Byte] =
    val hex = bytes.length.toHexString
    val header = s"$hex\r\n".getBytes("ISO-8859-1")
    val trailer = "\r\n".getBytes("ISO-8859-1")
    val out = new Array[Byte](header.length + bytes.length + trailer.length)
    System.arraycopy(header, 0, out, 0, header.length)
    System.arraycopy(bytes, 0, out, header.length, bytes.length)
    System.arraycopy(trailer, 0, out, header.length + bytes.length, trailer.length)
    out

  private def endStreaming(body: Array[Byte]): Future[Unit] =
    // Optional final chunk (if `end(body)` was called streaming).
    if body.nonEmpty then transport.write(chunkFrame(body))

    // Zero-chunk terminator: "0\r\n\r\n".
    val terminator = transport.write("0\r\n\r\n".getBytes("ISO-8859-1"))
    onFinish(shouldKeepAlive)
    terminator

  private def endOneShot(body: Array[Byte]): Future[Unit] =
    headersSent = true
    headers.getOrElseUpdate("Date", httpDateNow())
    headers("Content-Length") = body.length.toString

    val keepAlive = shouldKeepAlive
    if keepAlive then headers.getOrElseUpdate("Connection", "keep-alive")
    else headers("Connection") = "close"

    val buf = new StringBuilder
    buf ++= s"HTTP/$httpVersion ${_statusCode} ${_statusMessage}\r\n"
    headers.foreach((k, v) => buf ++= s"$k: $v\r\n")
    buf ++= "\r\n"

    val headerBytes = buf.toString.getBytes("ISO-8859-1")
    val response =
      if body.isEmpty then headerBytes
      else
        val out = new Array[Byte](headerBytes.length + body.length)
        System.arraycopy(headerBytes, 0, out, 0, headerBytes.length)
        System.arraycopy(body, 0, out, headerBytes.length, body.length)
        out

    val writeFuture = transport.write(response)
    onFinish(keepAlive)
    writeFuture
end Response

/** Cross-platform HTTP-date helper. Avoids `java.time` (Scala Native's coverage
  * is partial) by using an `Instant` for the JVM and platform-specific
  * implementations elsewhere via a shared facade.
  *
  * The shared file uses [[HttpDate.format]] (defined per-platform) so we can
  * keep the formatting consistent without dragging in a heavy time library.
  */
private[microserve] inline def httpDateNow(): String = HttpDate.format(System.currentTimeMillis())
