package io.github.edadma.microserve

import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, SocketChannel}
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.collection.mutable
import scala.concurrent.Future

class Response(
    private val channel: SocketChannel,
    private val loop: EventLoop,
    private val key: SelectionKey = null,
    private val httpVersion: String = "1.1",
    private val requestConnectionHeader: Option[String] = None,
    private val onFinish: Boolean => Unit = _ => (),
):
  private var _statusCode: Int = 200
  private var _statusMessage: String = "OK"
  private var _body: Array[Byte] = Array.empty
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

  private def shouldKeepAlive: Boolean =
    val connHeader = requestConnectionHeader.map(_.toLowerCase)
    if httpVersion == "1.1" then
      !connHeader.contains("close")
    else
      connHeader.contains("keep-alive")

  def end(body: Array[Byte] = Array.empty): Future[Unit] =
    if headersSent then return Future.unit
    headersSent = true

    _body = body
    headers.getOrElseUpdate("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(
      ZonedDateTime.now(ZoneId.of("GMT")),
    ))
    headers("Content-Length") = _body.length.toString

    val keepAlive = shouldKeepAlive
    if keepAlive then
      headers.getOrElseUpdate("Connection", "keep-alive")
    else
      headers("Connection") = "close"

    val buf = new StringBuilder
    buf ++= s"HTTP/$httpVersion $_statusCode $_statusMessage\r\n"
    headers.foreach((k, v) => buf ++= s"$k: $v\r\n")
    buf ++= "\r\n"

    val headerBytes = buf.toString.getBytes("ASCII")
    val response = Array.concat(headerBytes, _body)

    try
      val bb = ByteBuffer.wrap(response)
      while bb.hasRemaining do channel.write(bb)
    catch case _: Exception => () // client may have disconnected

    onFinish(keepAlive)
    Future.unit
