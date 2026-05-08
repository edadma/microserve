package io.github.edadma

import scala.concurrent.Future

package object microserve:

  /** Handler function type. Returns a `Future[Unit]` that completes when the
    * response has been emitted. Synchronous handlers can simply call
    * `res.send(...)` (which already returns `Future.successful(())` after
    * the bytes have been queued).
    */
  type RequestHandler = (Request, Response) => Future[Unit]

  /** Create a new server bound to the supplied handler. Picks up the
    * platform's `given Runtime`.
    *
    * Identical call site on JVM/JS/Native:
    * {{{
    *   val server = createServer { (req, res) =>
    *     req.path match
    *       case "/"     => res.send("hi")
    *       case _       => res.status(404).send("nope")
    *   }
    *   server.listen(3000) { () => println("listening") }
    *   server.run()  // no-op on JS
    * }}}
    */
  def createServer(handler: RequestHandler)(using runtime: Runtime): Server =
    new Server(handler, runtime)
end microserve
