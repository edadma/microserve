package io.github.edadma.microserve

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

private[microserve] object FsTestUtilsImpl extends FsTestUtils:

  @js.native @JSImport("fs", JSImport.Namespace)
  private object fs extends js.Object:
    def mkdtempSync(prefix: String): String = js.native
    def writeFileSync(path: String, data: String, encoding: String): Unit = js.native
    def unlinkSync(path: String): Unit = js.native
    def rmSync(path: String, options: js.Dynamic): Unit = js.native
    def existsSync(path: String): Boolean = js.native

  @js.native @JSImport("os", JSImport.Namespace)
  private object os extends js.Object:
    def tmpdir(): String = js.native

  @js.native @JSImport("path", JSImport.Namespace)
  private object path extends js.Object:
    def join(parts: String*): String = js.native

  def createTempDir(prefix: String): String =
    fs.mkdtempSync(path.join(os.tmpdir(), prefix))

  def writeFile(p: String, contents: String): Unit =
    fs.writeFileSync(p, contents, "utf8")

  def deleteFile(p: String): Unit =
    if fs.existsSync(p) then
      try fs.unlinkSync(p) catch case _: Throwable => ()

  def removeRecursive(p: String): Unit =
    if fs.existsSync(p) then
      try fs.rmSync(p, js.Dynamic.literal(recursive = true, force = true))
      catch case _: Throwable => ()
