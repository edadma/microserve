package io.github.edadma.microserve

/** A filesystem-event watcher. One watcher can watch multiple paths.
  *
  * All [[FsEvent]]s are delivered on the [[Runtime]]'s `executionContext`,
  * the same thread/loop the HTTP server runs on. Handlers therefore see
  * events under the same single-thread invariants as request handlers,
  * with no synchronization required between the two.
  *
  * Construct via `runtime.newFsWatcher()`.
  */
trait FsWatcher:

  /** Begin watching `path`. If `path` is a directory and `recursive` is
    * `true`, descendant files/directories are watched too. Returns a cancel
    * function for this specific watch (idempotent).
    *
    * On Linux, recursive watching of large trees may be expensive
    * (`inotify` requires one watch per directory and a kernel-level limit
    * applies). Plan accordingly for big content trees.
    *
    * The same watcher instance can call `watch` multiple times; each call
    * registers an independent subscription with its own cancel function.
    */
  def watch(path: String, recursive: Boolean = true)(onChange: FsEvent => Unit): () => Unit

  /** Tear down all subscriptions and release platform resources (kernel
    * watch descriptors, libuv handles, …). After `close()`, the watcher is
    * unusable; create a new one if you need to start watching again.
    */
  def close(): Unit
end FsWatcher

/** A single filesystem event. `path` is the absolute path of the file or
  * directory affected. The exact set of events fired for a given action is
  * platform-dependent — for example, on Linux a save-via-rename pattern
  * emits `Created` then `Deleted` for the temp file plus `Created` for the
  * final name; on macOS it more often surfaces as `Modified`.
  *
  * Treat the event stream as a *hint* that something changed: re-stat or
  * re-read the file to determine the new state if you need authoritative
  * information.
  */
final case class FsEvent(kind: FsEvent.Kind, path: String)

object FsEvent:
  enum Kind:
    case Created, Modified, Deleted
