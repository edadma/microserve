package io.github.edadma.microserve

import java.nio.file.{
  Files, FileSystems, Path, Paths, StandardWatchEventKinds => Kinds,
  WatchEvent, WatchKey, WatchService,
}
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

/** JVM `FsWatcher` backed by `java.nio.file.WatchService`.
  *
  * Recursive watching is implemented manually: at registration time we walk
  * the subtree and register each directory; whenever a `Created` event fires
  * for a sub-path that is itself a directory, we register that too. This
  * mirrors what `java.nio.file` itself doesn't provide (the WatchService API
  * is per-directory, not recursive).
  *
  * A single daemon thread blocks on `WatchService.take()`. Events are
  * marshalled onto the runtime's `executionContext` so user callbacks run on
  * the event-loop thread, same place HTTP handlers run.
  *
  * Tradeoffs vs. polling:
  *   - **Latency**: kernel-driven, sub-millisecond on Linux/macOS.
  *   - **macOS quirk**: under the hood the JVM uses a polling adapter on
  *     macOS (no kqueue WatchService) so events come in 1–10s after the
  *     change. Acceptable for dev-tool watching; if better latency is
  *     needed, switch to a third-party library like `fs.directory-watcher`.
  */
private[microserve] class NioFsWatcher(ec: ExecutionContext) extends FsWatcher:

  private val watcher: WatchService = FileSystems.getDefault.nn.newWatchService().nn

  /** One subscription = a registered watch + its callback. Multiple
    * subscriptions can target the same path; we keep them separate so each
    * gets its own cancel function.
    */
  private case class Subscription(rootPath: Path, recursive: Boolean, onChange: FsEvent => Unit)

  /** Maps each registered `WatchKey` to (the directory it watches, the set
    * of subscriptions whose tree contains it). Multiple subscriptions can
    * share a key — for example, two recursive watches that overlap.
    */
  private val keyToDir = new mutable.HashMap[WatchKey, Path]
  private val subscriptions = new mutable.HashSet[Subscription]
  private val keys = new mutable.HashMap[Path, WatchKey]

  // Synchronizes mutations to the maps above and the closed flag. The watch
  // thread reads them; callers (test thread, request handlers) write them.
  private val lock = new Object
  @volatile private var closed = false

  private val watchThread: Thread =
    val t = new Thread(() => loop(), "microserve-fs-watcher")
    t.setDaemon(true)
    t.start()
    t

  def watch(path: String, recursive: Boolean = true)(onChange: FsEvent => Unit): () => Unit =
    val root = Paths.get(path).nn.toAbsolutePath.nn
    val sub = Subscription(root, recursive, onChange)
    lock.synchronized {
      subscriptions += sub
      registerSubtree(root, recursive)
    }
    () =>
      lock.synchronized {
        subscriptions -= sub
        // Don't bother de-registering keys — they're cheap to leave registered
        // and another subscription may still want them. Closed-watcher cleanup
        // removes them.
      }

  def close(): Unit =
    lock.synchronized {
      if closed then return
      closed = true
    }
    try watcher.close()
    catch case _: Exception => ()
    // The watch thread will see the closed flag (via ClosedWatchServiceException
    // bubbling up from `take()`) and exit on its own.

  /** Register `dir` (if a directory) and, if `recursive`, every sub-directory
    * underneath it. Idempotent — registering the same directory twice replaces
    * the prior key without leaking watch handles.
    */
  private def registerSubtree(dir: Path, recursive: Boolean): Unit =
    if !Files.isDirectory(dir) then return
    registerOne(dir)
    if recursive then
      val stream = Files.walk(dir).nn
      try
        stream.iterator().nn.asScala.foreach { p =>
          val pp = p.nn
          if !pp.equals(dir) && Files.isDirectory(pp) then registerOne(pp)
        }
      finally stream.close()

  private def registerOne(dir: Path): Unit =
    keys.get(dir) match
      case Some(_) => () // already registered
      case None =>
        try
          val key = dir.register(watcher, Kinds.ENTRY_CREATE, Kinds.ENTRY_MODIFY, Kinds.ENTRY_DELETE).nn
          keys(dir) = key
          keyToDir(key) = dir
        catch case _: Exception => ()

  private def loop(): Unit =
    while !closed do
      val key =
        try watcher.take().nn
        catch case _: Throwable => return // closed or interrupted

      val dir = lock.synchronized(keyToDir.get(key))
      dir match
        case None =>
          // Stale key — reset and continue.
          try { val _ = key.reset() } catch case _: Throwable => ()
        case Some(d) =>
          val events = key.pollEvents().nn.asScala.toList
          events.foreach { e =>
            handleEvent(d, e.nn)
          }
          val valid = try key.reset() catch case _: Throwable => false
          if !valid then
            lock.synchronized {
              keyToDir -= key
              keys -= d
            }

  /** Translate one raw `WatchEvent`, deliver to interested subscriptions,
    * and (for recursive subscriptions) register newly-created directories.
    */
  private def handleEvent(dir: Path, e: WatchEvent[?]): Unit =
    val ctx = e.context()
    val rel: Path =
      if ctx.isInstanceOf[Path] then ctx.asInstanceOf[Path].nn else return
    val full = dir.resolve(rel).nn.toAbsolutePath.nn
    val kind = e.kind() match
      case Kinds.ENTRY_CREATE => FsEvent.Kind.Created
      case Kinds.ENTRY_MODIFY => FsEvent.Kind.Modified
      case Kinds.ENTRY_DELETE => FsEvent.Kind.Deleted
      case _                  => return // OVERFLOW etc.

    val event = FsEvent(kind, full.toString)

    val (toNotify, recursiveCreate) = lock.synchronized {
      val live = subscriptions.toList.filter(s => isUnderRoot(full, s.rootPath, s.recursive))
      val needsRecursiveRegister =
        kind == FsEvent.Kind.Created &&
          Files.isDirectory(full) &&
          live.exists(_.recursive)
      (live, needsRecursiveRegister)
    }

    if recursiveCreate then
      lock.synchronized(registerSubtree(full, recursive = true))

    toNotify.foreach { sub =>
      ec.execute(() => sub.onChange(event))
    }

  /** True iff `path` is under `root`, considering whether the subscription
    * is recursive. For a non-recursive subscription, only direct children
    * count.
    */
  private def isUnderRoot(path: Path, root: Path, recursive: Boolean): Boolean =
    if !path.startsWith(root) then false
    else if recursive then true
    else path.getParent.nn.equals(root)
end NioFsWatcher
