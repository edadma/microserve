package io.github.edadma.microserve

import scala.concurrent.{ExecutionContext, Future}

/** One connection's state: HTTP parser, idle-timeout, and the dispatch that
  * turns a fully-parsed request into a handler invocation.
  *
  * Lives in shared code because everything here is byte-stream logic that has
  * nothing to do with NIO selectors, Node sockets, or libuv handles. The only
  * platform contact is via the [[ConnectionTransport]] passed in.
  */
private[microserve] class ConnectionState(
    transport: ConnectionTransport,
    handler: RequestHandler,
    timers: Timers,
    onConnectionClosed: ConnectionState => Unit,
    isServerClosing: () => Boolean,
)(using ExecutionContext):

  private val parser = new HTTPRequestParser
  private var closed = false
  private var idle = true
  private var idleTimeoutCancel: () => Unit = null

  // Wire transport callbacks before any I/O can happen.
  transport.onClose(() => onTransportClose())
  transport.onRead(bytes => onBytes(bytes))
  resetIdleTimeout()

  /** True iff no request is currently in flight on this connection. The drain
    * code uses this to close idle keep-alive connections immediately while
    * letting active ones finish.
    */
  def isIdle: Boolean = idle

  def shutdownIfIdle(): Unit = if isIdle then closeConnection()

  private def resetIdleTimeout(): Unit =
    if idleTimeoutCancel != null then idleTimeoutCancel()
    idleTimeoutCancel = timers.setTimeout(30000) { () =>
      closeConnection()
    }

  private def cancelIdleTimeout(): Unit =
    if idleTimeoutCancel != null then
      idleTimeoutCancel()
      idleTimeoutCancel = null

  /** Feed a chunk into the parser. When the parser reaches FINAL we hand off
    * to `processRequest` and reset for the next request on the same connection
    * (keep-alive). Parser exceptions become a 400 + connection close.
    */
  private def onBytes(bytes: Array[Byte]): Unit =
    if closed then return
    resetIdleTimeout()

    try
      var i = 0
      while i < bytes.length do
        parser.send(bytes(i) & 0xFF)
        if parser.isFinal then
          processRequest()
          parser.reset()
        i += 1
    catch
      case e: Exception =>
        val res = new Response(transport)
        res.status(400).send(s"Bad Request: ${e.getMessage}")
        closeConnection()

  private def processRequest(): Unit =
    idle = false

    val queryMap = scala.collection.mutable.LinkedHashMap[String, String]()
    parser.query.foreach((k, v) => queryMap(k) = v)

    val connHeader = parser.headers.get("Connection")
    val httpVer = if parser.version.startsWith("HTTP/") then parser.version.drop(5) else parser.version

    val req = new Request(
      method = parser.method,
      path = parser.path,
      url = parser.url.toString,
      query = queryMap.toMap,
      version = parser.version,
      headers = parser.headers.clone(),
      body = parser.body.toArray,
      remoteAddress = transport.remoteAddress,
    )

    val res = new Response(
      transport = transport,
      httpVersion = httpVer,
      requestConnectionHeader = connHeader,
      onStreamStart = () =>
        // SSE / chunked streaming responses can run for minutes or hours;
        // the 30s idle timeout would close them despite the server actively
        // serving. Re-armed by the keep-alive branch in onFinish.
        cancelIdleTimeout(),
      onFinish = keepAlive =>
        idle = true
        if keepAlive && !isServerClosing() then resetIdleTimeout()
        else closeConnection(),
    )

    val fut =
      try handler(req, res)
      catch case e: Exception => Future.failed(e)

    fut.recover { case e: Exception =>
      // Handler failed before sending any response — emit a 500 if we still can.
      if !res.isSent then
        try
          val errRes = new Response(transport)
          errRes.status(500).send(s"Internal Server Error: ${e.getMessage}")
        catch case _: Exception => ()
        closeConnection()
    }

  private def onTransportClose(): Unit =
    if closed then return
    closed = true
    cancelIdleTimeout()
    onConnectionClosed(this)

  /** Close request originating from us (timeout / drain / handler error).
    * `transport.close()` will eventually fire `onTransportClose`, which
    * actually flips `closed` and fires the callback — so we don't need to
    * notify here directly.
    */
  def closeConnection(): Unit =
    if closed then return
    cancelIdleTimeout()
    transport.close()
end ConnectionState
