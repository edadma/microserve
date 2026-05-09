package io.github.edadma.microserve

import java.nio.file.{Files, Path, Paths, StandardOpenOption => OO}
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

private[microserve] object FsTestUtilsImpl extends FsTestUtils:
  def createTempDir(prefix: String): String =
    Files.createTempDirectory(prefix).nn.toAbsolutePath.nn.toString

  def writeFile(path: String, contents: String): Unit =
    val p = Paths.get(path).nn
    Files.write(
      p,
      contents.getBytes(UTF_8),
      OO.CREATE,
      OO.WRITE,
      OO.TRUNCATE_EXISTING,
    )

  def deleteFile(path: String): Unit =
    try { val _ = Files.deleteIfExists(Paths.get(path).nn) }
    catch case _: Exception => ()

  def removeRecursive(path: String): Unit =
    val root = Paths.get(path).nn
    if !Files.exists(root) then return
    val stream = Files.walk(root).nn
    try
      val all = stream.iterator().nn.asScala.toList.reverse // delete leaves first
      all.foreach { p => try { val _ = Files.deleteIfExists(p.nn) } catch case _: Exception => () }
    finally stream.close()
