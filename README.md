MicroServe
==========

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/microserve_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/microserve)](https://github.com/edadma/microserve/commits)
![GitHub](https://img.shields.io/github/license/edadma/microserve)
![Scala Version](https://img.shields.io/badge/Scala-3.8.1-blue.svg)

A lightweight, single-threaded HTTP server for the JVM built on `java.nio`. MicroServe uses a Node.js-style event loop with non-blocking I/O, timers, and a familiar `createServer` API.

## Quick Start

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.edadma" %% "microserve" % "0.1.0"
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

## Features

- Single-threaded, non-blocking I/O via `java.nio` selectors
- Node.js-style event loop with `nextTick`, `setTimeout`, and `setInterval`
- Hand-rolled HTTP/1.1 request parser with configurable limits
- Simple `Request`/`Response` API with helpers for text, HTML, and JSON
- No external dependencies

## License

ISC
