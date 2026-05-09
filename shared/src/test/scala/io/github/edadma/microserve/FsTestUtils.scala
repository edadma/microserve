package io.github.edadma.microserve

/** Tiny per-platform filesystem helper used by [[FsWatcherTests]]. The shape
  * of the API is uniform; the implementations live in each platform's
  * `src/test/scala/...` and are picked up by the linker.
  *
  * Kept small (just temp-dir creation + write/delete/cleanup) because real
  * filesystem APIs aren't part of microserve's runtime — they're only needed
  * by the watcher tests.
  */
private[microserve] trait FsTestUtils:
  /** Create a fresh temp directory whose name starts with `prefix`. Returns
    * the absolute path.
    */
  def createTempDir(prefix: String): String

  /** Write `contents` to `path` (UTF-8). Overwrites if the file exists. */
  def writeFile(path: String, contents: String): Unit

  /** Delete a single file. Silently ignores non-existence. */
  def deleteFile(path: String): Unit

  /** Recursively delete a directory and everything under it. Silently ignores
    * paths that don't exist.
    */
  def removeRecursive(path: String): Unit

private[microserve] object FsTestUtils extends FsTestUtils:
  // Each platform provides a `FsTestUtilsImpl` object; this wrapper just
  // delegates so test code can `FsTestUtils.createTempDir(...)` portably.
  private val impl: FsTestUtils = FsTestUtilsImpl

  def createTempDir(prefix: String): String      = impl.createTempDir(prefix)
  def writeFile(path: String, contents: String): Unit = impl.writeFile(path, contents)
  def deleteFile(path: String): Unit             = impl.deleteFile(path)
  def removeRecursive(path: String): Unit        = impl.removeRecursive(path)
