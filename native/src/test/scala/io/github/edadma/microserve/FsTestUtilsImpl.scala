package io.github.edadma.microserve

import java.nio.file.{Files, Paths, StandardOpenOption => OO}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/** Native test util — Scala Native 0.5 implements enough of `java.nio.file`
  * (Files / Paths / WatchService is the gap, not file ops) for the same
  * pattern as JVM. If we hit a missing API we'll switch to libuv's `uv_fs_*`
  * functions, but starting with the simpler java.nio path.
  */
private[microserve] object FsTestUtilsImpl extends FsTestUtils:
  def createTempDir(prefix: String): String =
    Files.createTempDirectory(prefix).toAbsolutePath.toString

  def writeFile(p: String, contents: String): Unit =
    val path = Paths.get(p)
    Files.write(
      path,
      contents.getBytes(UTF_8),
      OO.CREATE,
      OO.WRITE,
      OO.TRUNCATE_EXISTING,
    )

  def deleteFile(p: String): Unit =
    try { val _ = Files.deleteIfExists(Paths.get(p)) }
    catch case _: Exception => ()

  def removeRecursive(p: String): Unit =
    val root = Paths.get(p)
    if !Files.exists(root) then return
    val stream = Files.walk(root)
    try
      val all = stream.iterator().asScala.toList.reverse
      all.foreach { x => try { val _ = Files.deleteIfExists(x) } catch case _: Exception => () }
    finally stream.close()
