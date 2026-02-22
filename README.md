MicroServe
==========

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/microserve_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/microserve)](https://github.com/edadma/microserve/commits)
![GitHub](https://img.shields.io/github/license/edadma/microserve)
![Scala Version](https://img.shields.io/badge/Scala-3.8.1-blue.svg)

A lightweight, single-threaded HTTP server for the JVM built on `java.nio`. MicroServe uses a Node.js-style event loop with non-blocking I/O, `Future`-based async handlers, HTTP/1.1 keep-alive, and a familiar `createServer` API.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.edadma" %% "microserve" % "0.2.0"
```

## Example

```scala
import io.github.edadma.microserve.*

@main def run(): Unit =
  val loop = new EventLoop

  val server = createServer(loop) { (req, res) =>
    req.path match
      case "/"     => res.send("Hello from MicroServe!\n")
      case "/json" => res.sendJson("""{"message": "hello"}""" + "\n")
      case _       => res.status(404).send("Not Found\n")
  }

  server.listen(3000) { () =>
    println("MicroServe listening on http://localhost:3000")
  }

  loop.run()
```

Handlers return `Future[Unit]` — the response methods (`send`, `sendJson`, `sendHtml`, `end`) return `Future.unit`, so synchronous handlers just work. For async work, use the loop's `ExecutionContext`:

```scala
import scala.concurrent.Future

val loop = new EventLoop
given scala.concurrent.ExecutionContext = loop.executionContext

val server = createServer(loop) { (req, res) =>
  Future {
    val result = someComputation()
    res.send(result)
  }.flatten
}
```

## Graceful Shutdown

`server.close()` stops accepting new connections and closes idle keep-alive connections immediately, while letting in-flight requests finish. The drain callback fires once all active requests have completed:

```scala
server.close { () =>
  println("All connections drained")
  loop.stop()
}
```

## Features

- Single-threaded, non-blocking I/O via `java.nio` selectors
- Node.js-style event loop with microtask/macrotask separation (`nextTick`, `setImmediate`, `setTimeout`, `setInterval`)
- `ExecutionContext` for running `Future` callbacks on the event loop thread
- Ref-counted lifecycle — loop exits automatically when all work is done
- HTTP/1.1 keep-alive with 30-second idle timeouts
- Graceful shutdown — in-flight requests complete, idle connections close immediately
- Hand-rolled HTTP/1.1 request parser with configurable limits
- Simple `Request`/`Response` API with helpers for text, HTML, and JSON
- No external dependencies

## License

ISC
