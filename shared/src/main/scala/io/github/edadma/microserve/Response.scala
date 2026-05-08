package io.github.edadma.microserve

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** The platform-neutral HTTP response. Composes the wire bytes (status line,
  * headers, body) and delegates the actual write to a `ConnectionTransport`
  * supplied at construction.
  *
  * Calling any terminal method (`send`, `sendJson`, `sendHtml`, `sendStatus`,
  * `end`) more than once is a no-op — the second call returns
  * `Future.successful(())` immediately, mirroring Node.js semantics.
  */
class Response private[microserve] (
    private val transport: ConnectionTransport,
    private val httpVersion: String = "1.1",
    private val requestConnectionHeader: Option[String] = None,
    private val onFinish: Boolean => Unit = _ => (),
):
  private var _statusCode: Int = 200
  private var _statusMessage: String = "OK"
  private val headers = mutable.LinkedHashMap[String, String]()
  private var headersSent = false

  def isSent: Boolean = headersSent

  def status(code: Int): Response =
    _statusCode = code
    _statusMessage = HTTP.statusMessageString(code)
    this

  def set(key: String, value: Any): Response =
    headers(key) = String.valueOf(value)
    this

  def writeHead(code: Int, hdrs: Map[String, String] = Map.empty): Response =
    status(code)
    hdrs.foreach((k, v) => headers(k) = v)
    this

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

  /** True if this is HTTP/1.1 without `Connection: close`, or HTTP/1.0 with an
    * explicit `Connection: keep-alive`. Matches RFC 7230 §6.3.
    */
  private def shouldKeepAlive: Boolean =
    val connHeader = requestConnectionHeader.map(_.toLowerCase)
    if httpVersion == "1.1" then !connHeader.contains("close")
    else connHeader.contains("keep-alive")

  /** Compose the HTTP response and hand the bytes to the transport.
    *
    * The returned `Future` completes when the transport has accepted the bytes
    * for delivery. The connection-keep-alive decision is made here and reported
    * to `onFinish` regardless of whether the write itself succeeds — that's
    * deliberate: `onFinish(false)` triggers connection close on the next read,
    * which is what we want even if the write failed.
    */
  def end(body: Array[Byte] = Array.empty): Future[Unit] =
    if headersSent then return Future.successful(())
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
  end end
end Response

/** Cross-platform HTTP-date helper. Avoids `java.time` (Scala Native's coverage
  * is partial) by using an `Instant` for the JVM and platform-specific
  * implementations elsewhere via a shared facade.
  *
  * The shared file uses [[HttpDate.format]] (defined per-platform) so we can
  * keep the formatting consistent without dragging in a heavy time library.
  */
private[microserve] inline def httpDateNow(): String = HttpDate.format(System.currentTimeMillis())
