package io.github.edadma.microserve

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, ServerSocketChannel, SocketChannel}
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

type RequestHandler = (Request, Response) => Future[Unit]

def createServer(loop: EventLoop)(handler: RequestHandler): Server =
  new Server(loop, handler)

class Server(val loop: EventLoop, handler: RequestHandler):
  private val serverChannel = ServerSocketChannel.open()
  private val readBufferSize = 8192
  private var serverKey: SelectionKey = null
  private var _activeConnections = 0
  private var _closing = false
  private var _onDrain: Option[() => Unit] = None
  private val _connections = mutable.Set[ConnectionState]()

  private given scala.concurrent.ExecutionContext = loop.executionContext

  def actualPort: Int = serverChannel.socket().getLocalPort

  def listen(port: Int, host: String = "0.0.0.0")(onListening: () => Unit = () => ()): Unit =
    serverChannel.configureBlocking(false)
    serverChannel.bind(new InetSocketAddress(host, port))

    // Register for ACCEPT events
    serverKey = loop.register(serverChannel, SelectionKey.OP_ACCEPT, new AcceptHandler)
    loop.ref() // server channel holds a ref

    // Fire the listening callback on the next tick
    loop.nextTick(onListening)

  private def connectionOpened(): Unit =
    _activeConnections += 1

  private def connectionClosed(): Unit =
    _activeConnections -= 1
    if _closing && _activeConnections == 0 then
      _onDrain.foreach(cb => loop.nextTick(cb))
      _onDrain = None

  def close(onDrain: () => Unit = () => ()): Unit =
    if _closing then return
    _closing = true

    // Stop accepting new connections
    if serverKey != null then serverKey.cancel()
    try serverChannel.close()
    catch case _: Exception => ()
    loop.unref() // release server channel ref

    // Force-close all active connections
    _connections.toList.foreach(_.closeConnection())

    if _activeConnections == 0 then
      loop.nextTick(onDrain)
    else
      _onDrain = Some(onDrain)

  /** Handles new connection accepts on the server socket. */
  private class AcceptHandler extends SelectionKeyHandler:
    def handle(key: SelectionKey): Unit =
      val server = key.channel().asInstanceOf[ServerSocketChannel]
      val client = server.accept()

      if client != null then
        client.configureBlocking(false)
        val selKey = loop.register(client, SelectionKey.OP_READ, null)
        val connState = new ConnectionState(client, selKey)
        selKey.attach(connState)
        _connections += connState
        connectionOpened()
        loop.ref() // each connection holds a ref

  /** Per-connection state: parser + read buffer. */
  private class ConnectionState(val channel: SocketChannel, val selectionKey: SelectionKey) extends SelectionKeyHandler:
    private val parser = new HTTPRequestParser
    private val readBuffer = ByteBuffer.allocate(readBufferSize)
    private var closed = false
    private var idleTimeoutCancel: (() => Unit) = null

    resetIdleTimeout()

    private def resetIdleTimeout(): Unit =
      if idleTimeoutCancel != null then idleTimeoutCancel()
      idleTimeoutCancel = loop.setTimeout(30000) { () =>
        closeConnection()
      }

    private def cancelIdleTimeout(): Unit =
      if idleTimeoutCancel != null then
        idleTimeoutCancel()
        idleTimeoutCancel = null

    def handle(key: SelectionKey): Unit =
      if !key.isReadable || closed then return

      resetIdleTimeout()
      readBuffer.clear()
      val bytesRead =
        try channel.read(readBuffer)
        catch
          case _: Exception =>
            closeConnection()
            return

      if bytesRead == -1 then
        closeConnection()
        return

      readBuffer.flip()

      try
        var i = 0
        while i < bytesRead do
          parser.send(readBuffer.get(i) & 0xFF)

          if parser.isFinal then
            processRequest()
            parser.reset()

          i += 1
      catch
        case e: Exception =>
          // Bad request — send 400 and close
          val res = new Response(channel, loop)
          res.status(400).send(s"Bad Request: ${e.getMessage}")
          closeConnection()

    private def processRequest(): Unit =
      val queryMap = mutable.LinkedHashMap[String, String]()
      parser.query.foreach((k, v) => queryMap(k) = v)

      val remoteAddr =
        try channel.getRemoteAddress.toString
        catch case _: Exception => "unknown"

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
        remoteAddress = remoteAddr,
      )

      val res = new Response(
        channel = channel,
        loop = loop,
        key = selectionKey,
        httpVersion = httpVer,
        requestConnectionHeader = connHeader,
        onFinish = keepAlive =>
          if keepAlive && !_closing then
            resetIdleTimeout()
          else
            closeConnection(),
      )

      val fut = try handler(req, res) catch case e: Exception => Future.failed(e)
      fut.recover { case e: Exception =>
        if !res.isSent then
          try
            val errRes = new Response(channel, loop)
            errRes.status(500).send(s"Internal Server Error: ${e.getMessage}")
          catch case _: Exception => ()
          closeConnection()
      }

    private[Server] def closeConnection(): Unit =
      if closed then return
      closed = true
      cancelIdleTimeout()
      _connections -= this
      selectionKey.cancel()
      try channel.close()
      catch case _: Exception => ()
      loop.unref() // release connection ref
      connectionClosed()
