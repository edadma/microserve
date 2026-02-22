package io.github.edadma.microserve

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, ServerSocketChannel, SocketChannel}
import scala.collection.mutable

type RequestHandler = (Request, Response) => Unit

/** Creates an HTTP server, Node's createServer equivalent. */
def createServer(loop: EventLoop)(handler: RequestHandler): Server =
  new Server(loop, handler)

class Server(val loop: EventLoop, handler: RequestHandler):
  private val serverChannel = ServerSocketChannel.open()
  private val readBufferSize = 8192

  def listen(port: Int, host: String = "0.0.0.0")(onListening: () => Unit = () => ()): Unit =
    serverChannel.configureBlocking(false)
    serverChannel.bind(new InetSocketAddress(host, port))

    // Register for ACCEPT events — new connections arrive here
    loop.register(serverChannel, SelectionKey.OP_ACCEPT, new AcceptHandler)

    // Fire the listening callback on the next tick, just like Node does
    loop.nextTick(onListening)

  def close(): Unit =
    try serverChannel.close()
    catch case _: Exception => ()

  /** Handles new connection accepts on the server socket. */
  private class AcceptHandler extends SelectionKeyHandler:
    def handle(key: SelectionKey): Unit =
      val server = key.channel().asInstanceOf[ServerSocketChannel]
      val client = server.accept()

      if client != null then
        client.configureBlocking(false)

        // Each connection gets its own parser and read buffer
        val connState = new ConnectionState(client)
        client.register(loop.selector, SelectionKey.OP_READ, connState)

  /** Per-connection state: parser + read buffer. */
  private class ConnectionState(val channel: SocketChannel) extends SelectionKeyHandler:
    private val parser = new HTTPRequestParser
    private val readBuffer = ByteBuffer.allocate(readBufferSize)

    def handle(key: SelectionKey): Unit =
      if !key.isReadable then return

      readBuffer.clear()
      val bytesRead =
        try channel.read(readBuffer)
        catch
          case _: Exception =>
            closeConnection(key)
            return

      if bytesRead == -1 then
        closeConnection(key)
        return

      readBuffer.flip()

      try
        var i = 0
        while i < bytesRead do
          parser.send(readBuffer.get(i) & 0xFF)

          if parser.isFinal then
            processRequest(channel)
            parser.reset()

          i += 1
      catch
        case e: Exception =>
          // Bad request — send 400 and close
          val res = new Response(channel, loop)
          res.status(400).send(s"Bad Request: ${e.getMessage}")
          closeConnection(key)

    private def processRequest(channel: SocketChannel): Unit =
      val queryMap = mutable.LinkedHashMap[String, String]()
      parser.query.foreach((k, v) => queryMap(k) = v)

      val remoteAddr =
        try channel.getRemoteAddress.toString
        catch case _: Exception => "unknown"

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

      val res = new Response(channel, loop)

      try handler(req, res)
      catch
        case e: Exception =>
          try
            val errRes = new Response(channel, loop)
            errRes.status(500).send(s"Internal Server Error: ${e.getMessage}")
          catch case _: Exception => ()

    private def closeConnection(key: SelectionKey): Unit =
      key.cancel()
      try channel.close()
      catch case _: Exception => ()
