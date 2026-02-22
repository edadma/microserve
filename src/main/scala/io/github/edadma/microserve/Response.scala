package io.github.edadma.microserve

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, ZonedDateTime}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class Response(private val channel: SocketChannel, private val loop: EventLoop):
  private var _statusCode: Int = 200
  private var _statusMessage: String = "OK"
  private var _body: Array[Byte] = Array.empty
  private val headers = mutable.LinkedHashMap[String, String]()
  private var headersSent = false

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

  def send(data: String): Unit =
    headers.getOrElseUpdate("Content-Type", "text/plain; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendHtml(data: String): Unit =
    headers.getOrElseUpdate("Content-Type", "text/html; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendJson(data: String): Unit =
    headers.getOrElseUpdate("Content-Type", "application/json; charset=UTF-8")
    end(data.getBytes("UTF-8"))

  def sendStatus(code: Int): Unit =
    status(code)
    send(s"${HTTP.statusMessageString(code)}")

  def end(body: Array[Byte] = Array.empty): Unit =
    if headersSent then return
    headersSent = true

    _body = body
    headers.getOrElseUpdate("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(
      ZonedDateTime.now(ZoneId.of("GMT")),
    ))
    headers("Content-Length") = _body.length.toString
    headers.getOrElseUpdate("Connection", "close")

    val buf = new StringBuilder
    buf ++= s"HTTP/1.1 $_statusCode $_statusMessage\r\n"
    headers.foreach((k, v) => buf ++= s"$k: $v\r\n")
    buf ++= "\r\n"

    val headerBytes = buf.toString.getBytes("ASCII")
    val response = Array.concat(headerBytes, _body)

    try
      val bb = ByteBuffer.wrap(response)
      while bb.hasRemaining do channel.write(bb)
    catch case _: Exception => () // client may have disconnected

    try channel.close()
    catch case _: Exception => ()
