MicroServe
==========

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/microserve_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/microserve)](https://github.com/edadma/microserve/commits)
![GitHub](https://img.shields.io/github/license/edadma/microserve)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)

A lightweight, cross-platform HTTP server for Scala 3 — same API on the JVM (`java.nio`), Scala.js (Node `net`), and Scala Native (libuv). Single-threaded, Node-style event loop, `Future`-based async handlers, HTTP/1.1 keep-alive, chunked-transfer streaming, a per-connection `Response.onClose` hook, and a cross-platform filesystem watcher.

## Quick start

```scala
libraryDependencies += "io.github.edadma" %%% "microserve" % "0.5.0"
```

(Use `%%` for JVM-only.)

```scala
import io.github.edadma.microserve.*

@main def run(): Unit =
  val server = createServer { (req, res) =>
    req.path match
      case "/"     => res.send("Hello from MicroServe!")
      case "/json" => res.sendJson("""{"message":"hello"}""")
      case _       => res.status(404).send("Not Found")
  }

  server.listen(3000) { () =>
    println("listening on http://localhost:3000")
  }

  server.run()  // blocks on JVM/Native; returns immediately on JS (Node owns the loop)
```

The handler signature is `(Request, Response) => Future[Unit]`. Synchronous handlers just call `res.send(...)`/`res.sendJson(...)`/etc. — those return `Future.successful(())` so the type works without ceremony. Async work uses the runtime's execution context:

```scala
given scala.concurrent.ExecutionContext = summon[Runtime].executionContext

val server = createServer { (req, res) =>
  Future {
    val result = someComputation()
    res.send(result)
  }.flatten
}
```

## Bind error handling

`server.listen` takes an optional `onError` callback that fires (asynchronously, on the loop) when the bind fails — the most common cause being a port already in use. After `onError`, the server is unusable; construct a fresh one to retry on a different port.

```scala
def bindWithRetry(port: Int, retriesLeft: Int): Unit =
  val server = createServer(handler)
  server.listen(port, "127.0.0.1")(
    onListening = () => println(s"listening on $port"),
    onError = e =>
      if retriesLeft > 0 then
        println(s"port $port busy, trying ${port + 1}")
        bindWithRetry(port + 1, retriesLeft - 1)
      else
        println(s"giving up: $e"),
  )
```

## Streaming responses (SSE, NDJSON, large downloads)

`Response` has two modes, picked implicitly:

- **One-shot**: `send` / `sendJson` / `sendHtml` / `sendStatus` / `end(body)` compose status line + headers + body in one buffer with `Content-Length`, write once, close (or keep-alive).
- **Streaming**: calling `write(chunk)` before any one-shot terminator switches the response to `Transfer-Encoding: chunked` — headers flush on first `write`, each subsequent `write` emits a chunk frame, `end()` (or `end(body)` with optional final bytes) emits the zero-chunk terminator.

Server-Sent Events example:

```scala
val server = createServer { (req, res) =>
  if req.path == "/events" then
    res.writeHead(200, Map(
      "Content-Type"  -> "text/event-stream",
      "Cache-Control" -> "no-cache",
    ))
    res.write("event: greet\ndata: hello\n\n")
    res.write("event: greet\ndata: world\n\n")
    res.end()
  else
    res.status(404).send("Not Found")
}
```

Streaming responses get the idle-timeout suppressed for the duration of the stream, so a long-lived SSE connection isn't killed at the 30 s threshold. Once `end()` runs, the keep-alive timer is re-armed automatically.

## Detecting client disconnect

A long-lived streaming response can register a callback that fires when the underlying TCP connection drops — peer closed the tab, network dropped, server shutdown:

```scala
val sseSubscribers = scala.collection.mutable.Set[Response]()

val server = createServer { (req, res) =>
  if req.path == "/events" then
    res.writeHead(200, Map("Content-Type" -> "text/event-stream"))
    sseSubscribers.add(res)
    res.onClose(() => sseSubscribers.remove(res))
    Future.successful(())
  else
    res.send("ok")
}

// later, broadcasting a server-initiated event to all live subscribers:
sseSubscribers.toList.foreach { s =>
  s.write("event: refresh\ndata: 1\n\n")
}
```

`onClose` fires exactly once per response. For one-shot responses it typically fires after the response went out; for streaming responses it fires whenever the wire drops, even if `end()` was never called.

## Graceful shutdown

`server.close()` stops accepting new connections, closes idle keep-alive connections immediately, and lets in-flight requests finish. The drain callback fires once the last connection is gone:

```scala
server.close { () =>
  println("all connections drained")
}
```

## Filesystem watcher

A cross-platform `FsWatcher` for live-reload, hot-reload, or rebuild-on-save flows. Backed by `java.nio.file.WatchService` on the JVM, Node's `fs.watch` on JS, and libuv's `uv_fs_event_t` on Native.

```scala
val watcher = summon[Runtime].newFsWatcher()
val cancel = watcher.watch("/path/to/dir", recursive = true) { event =>
  // event: FsEvent(kind, path) — kind is Created | Modified | Deleted
  println(s"${event.kind}: ${event.path}")
}

// later:
cancel()        // unsubscribe one watch
watcher.close() // tear down the watcher entirely
```

Events are delivered on the runtime's `executionContext` — the same thread the HTTP server runs on, so handlers see them under the same single-thread invariants as request handlers.

Treat the event stream as a *hint* that something changed: re-stat or re-read the file to determine the new state. The exact set of events for a given action varies by platform (a Linux save-via-rename emits `Created` + `Deleted` for the temp file; macOS more often surfaces a single `Modified`). On macOS the JVM `WatchService` uses a polling adapter — events arrive 1–10 s after the change. The Native libuv path uses FSEvents with a ~250 ms coalescing window. Plan timeouts accordingly.

## Features

- **Cross-platform**: same `createServer`/`listen`/`Response`/`FsWatcher` API on JVM, JS, and Native.
- Single-threaded, non-blocking I/O via `java.nio` selectors / Node `net` / libuv.
- Node-style event loop with microtasks, macrotasks (`nextTick`, `setImmediate`, `setTimeout`).
- `ExecutionContext` for `Future` callbacks running on the loop thread.
- Ref-counted lifecycle — loop exits automatically when all work is done.
- HTTP/1.1 keep-alive with 30 s idle timeout (suppressed during streaming responses).
- Chunked-transfer streaming for SSE / NDJSON / large downloads.
- Per-`Response` `onClose` callback for live disconnect detection.
- Graceful shutdown — in-flight requests complete, idle connections close immediately.
- Bind-error reporting via `listen(...)(onListening, onError)`.
- Hand-rolled HTTP/1.1 request parser with configurable limits.
- No external dependencies on JVM/JS; libuv + spritzsn-async on Native.

## License

ISC
