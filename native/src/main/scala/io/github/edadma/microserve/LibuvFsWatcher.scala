package io.github.edadma.microserve

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import io.github.spritzsn.libuv as uv
import io.github.spritzsn.libuv.{
  defaultLoop, FsEvent => UvFsEvent,
  UV_RENAME, UV_CHANGE, UV_FS_EVENT_RECURSIVE,
}

/** Native `FsWatcher` backed by `spritzsn/libuv`'s `uv_fs_event_t`. One libuv
  * handle per `watch(...)` call. Recursive watching uses libuv's
  * `UV_FS_EVENT_RECURSIVE` flag, which works on macOS and Windows; on Linux
  * libuv falls back to non-recursive (per the man page) so callers wanting
  * recursion under Linux still need the manual subdirectory walk — same
  * caveat as Node's fs.watch.
  *
  * Events fire on the libuv loop thread; we marshal them onto the runtime's
  * `executionContext` so user callbacks see the same threading model as
  * HTTP handlers. (In practice, `executionContext` *is* the libuv loop on
  * Native, so the trip is effectively a same-thread queue hop — but this
  * keeps the contract uniform with JVM and JS where the EC is a real
  * scheduler.)
  */
private[microserve] class LibuvFsWatcher(ec: ExecutionContext) extends FsWatcher:

  /** A single (subscription, libuv handle) pair. Storing the handle lets us
    * stop + dispose it on cancel().
    */
  private case class Subscription(rootPath: String, handle: UvFsEvent, onChange: FsEvent => Unit)

  private val subs = mutable.HashSet.empty[Subscription]
  private var closed = false

  def watch(path: String, recursive: Boolean = true)(onChange: FsEvent => Unit): () => Unit =
    if closed then return () => ()
    val handle = defaultLoop.fsEvent
    val flags = if recursive then UV_FS_EVENT_RECURSIVE else 0
    val sub = Subscription(path, handle, onChange)
    subs += sub

    handle.start(path, flags) { (h, filename, events, status) =>
      if status >= 0 then
        // libuv events can be UV_RENAME (creation/deletion) or UV_CHANGE
        // (modification). We can't tell create from delete from the flag
        // alone — the caller must re-stat if it cares. Heuristic: emit
        // Created for rename (consistent with most rename semantics:
        // "something appeared") and Modified for change.
        val full =
          if filename.isEmpty then path
          else if filename.startsWith("/") then filename
          else s"$path/$filename"
        val kind =
          if (events & UV_CHANGE) != 0 then FsEvent.Kind.Modified
          else FsEvent.Kind.Created // UV_RENAME — best effort
        ec.execute(() => sub.onChange(FsEvent(kind, full)))
    }

    () => cancel(sub)

  def close(): Unit =
    if closed then return
    closed = true
    subs.toList.foreach(cancel)
    subs.clear()

  private def cancel(sub: Subscription): Unit =
    if subs.contains(sub) then
      try sub.handle.stop catch case _: Throwable => ()
      try sub.handle.dispose() catch case _: Throwable => ()
      subs -= sub
end LibuvFsWatcher
