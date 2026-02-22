package io.github.edadma.microserve

@main def run(): Unit =
  val loop = new EventLoop

  val server = createServer(loop) { (req, res) =>
    req.path match
      case "/" =>
        res.writeHead(200, Map("Content-Type" -> "text/plain"))
        res.end(s"Hello from MicroServe! You requested: ${req.url}\n".getBytes)

      case "/json" =>
        res.sendJson("""{"message": "Hello, World!", "server": "MicroServe"}""" + "\n")

      case "/html" =>
        res.sendHtml("""
          |<!DOCTYPE html>
          |<html>
          |  <head><title>MicroServe</title></head>
          |  <body>
          |    <h1>Hello from MicroServe!</h1>
          |    <p>A single-threaded HTTP server powered by java.nio</p>
          |  </body>
          |</html>
          |""".stripMargin)

      case _ =>
        res.status(404).send(s"Not Found: ${req.path}\n")
  }

  server.listen(3000) { () =>
    println("MicroServe listening on http://localhost:3000")
  }

  // This blocks — just like Node's event loop
  loop.run()
