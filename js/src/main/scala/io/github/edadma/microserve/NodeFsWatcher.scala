package io.github.edadma.microserve

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/** Minimal facade over Node's `fs.watch` plus a couple of helpers from the
  * `path` and `fs` modules. `fs.watch` natively supports recursive watching
  * on macOS and Windows; on Linux it does not, and the caller is expected to
  * register one watcher per directory.
  */
private[microserve] object NodeFs:
  @js.native @JSImport("fs", JSImport.Namespace)
  object fs extends js.Object:
    def watch(path: String, options: js.Dynamic, listener: js.Function2[String, String, Unit]): FSWatcher = js.native
    def watch(path: String, listener: js.Function2[String, String, Unit]): FSWatcher = js.native
    def existsSync(path: String): Boolean = js.native
    def statSync(path: String): Stats = js.native
    def readdirSync(path: String): js.Array[String] = js.native

  @js.native @JSImport("path", JSImport.Namespace)
  object path extends js.Object:
    def join(parts: String*): String = js.native
    def resolve(parts: String*): String = js.native
    def isAbsolute(p: String): Boolean = js.native

  @js.native @JSImport("os", JSImport.Namespace)
  object os extends js.Object:
    def platform(): String = js.native

  @js.native
  trait FSWatcher extends js.Object:
    def close(): Unit = js.native

  @js.native
  trait Stats extends js.Object:
    def isDirectory(): Boolean = js.native
end NodeFs

import NodeFs.*

/** Node-backed `FsWatcher`.
  *
  * On macOS / Windows the underlying `fs.watch(..., { recursive: true })`
  * does the recursive walk in native code — one JS watcher covers the whole
  * tree. On Linux, `recursive` is unsupported (Node throws), so we walk the
  * directory ourselves with `readdirSync`+`statSync` and register one
  * watcher per subdirectory; new directories created later get their own
  * watcher when the rename event fires.
  */
private[microserve] class NodeFsWatcher(ec: ExecutionContext) extends FsWatcher:

  private val isLinux: Boolean = os.platform() == "linux"

  private case class Subscription(rootPath: String, recursive: Boolean, onChange: FsEvent => Unit)

  private val subscriptions = mutable.HashSet.empty[Subscription]
  // Track watchers per (subscription, dir) so we can close them on cancel /
  // close. On macOS/Win there's exactly one watcher per recursive subscription;
  // on Linux there may be many.
  private val watchersBySub = mutable.HashMap.empty[Subscription, mutable.ListBuffer[FSWatcher]]
  private var closed = false

  def watch(path: String, recursive: Boolean = true)(onChange: FsEvent => Unit): () => Unit =
    if closed then return () => ()
    val abs = NodeFs.path.resolve(path)
    val sub = Subscription(abs, recursive, onChange)
    subscriptions += sub
    watchersBySub(sub) = mutable.ListBuffer.empty

    if recursive && !isLinux then registerSingle(sub, abs, recursive = true)
    else if recursive then registerLinuxRecursive(sub, abs)
    else registerSingle(sub, abs, recursive = false)

    () => cancel(sub)

  def close(): Unit =
    if closed then return
    closed = true
    watchersBySub.values.foreach { list =>
      list.foreach { w => try w.close() catch case _: Throwable => () }
    }
    watchersBySub.clear()
    subscriptions.clear()

  private def cancel(sub: Subscription): Unit =
    watchersBySub.remove(sub).foreach { list =>
      list.foreach { w => try w.close() catch case _: Throwable => () }
    }
    subscriptions -= sub

  /** Register a single watcher (recursive flag passed through to Node). The
    * 'rename'/'change' event types are mapped to our [[FsEvent.Kind]];
    * `rename` covers both creation and deletion in Node so we re-stat to
    * disambiguate.
    */
  private def registerSingle(sub: Subscription, dirOrFile: String, recursive: Boolean): Unit =
    val opts = js.Dynamic.literal(recursive = recursive)
    val w = fs.watch(
      dirOrFile,
      opts,
      (eventType: String, filename: String) => {
        // filename can be null for some platforms / event types.
        val rel = if filename == null || filename.isEmpty then "" else filename
        val full =
          if rel.isEmpty then dirOrFile
          else if NodeFs.path.isAbsolute(rel) then rel
          else NodeFs.path.join(dirOrFile, rel)
        // Translate Node's eventType to one of our FsEvent.Kind, or None for
        // anything we don't care about. Using Option here instead of an
        // early `return` keeps Scala 3 happy — non-local returns from inside
        // a lambda are no longer supported.
        val maybeEvent: Option[FsEvent] = eventType match
          case "rename" =>
            // Existing → created; non-existing → deleted.
            Some(
              if fs.existsSync(full) then FsEvent(FsEvent.Kind.Created, full)
              else FsEvent(FsEvent.Kind.Deleted, full),
            )
          case "change" =>
            Some(FsEvent(FsEvent.Kind.Modified, full))
          case _ => None

        maybeEvent.foreach { event =>
          ec.execute(() => sub.onChange(event))

          // On Linux, if a new directory appeared inside a recursive root,
          // watch it too.
          if isLinux && sub.recursive && event.kind == FsEvent.Kind.Created &&
            fs.existsSync(full) && fs.statSync(full).isDirectory()
          then registerLinuxRecursive(sub, full)
        }
      },
    )
    watchersBySub.get(sub).foreach(_ += w)

  /** Linux fallback: register `dir` plus every existing sub-directory, one
    * watcher each (non-recursive). Directories created later are picked up
    * via the 'rename' handler in `registerSingle`.
    */
  private def registerLinuxRecursive(sub: Subscription, dir: String): Unit =
    if !fs.existsSync(dir) then return
    if !fs.statSync(dir).isDirectory() then
      registerSingle(sub, dir, recursive = false)
      return
    registerSingle(sub, dir, recursive = false)
    val entries = fs.readdirSync(dir)
    var i = 0
    while i < entries.length do
      val child = NodeFs.path.join(dir, entries(i))
      try
        if fs.statSync(child).isDirectory() then
          registerLinuxRecursive(sub, child)
      catch case _: Throwable => ()
      i += 1
end NodeFsWatcher
