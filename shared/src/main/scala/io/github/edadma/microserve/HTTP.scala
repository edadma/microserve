package io.github.edadma.microserve

object HTTP:
  val statusMessage: Map[Int, String] =
    Map(
      100 -> "Continue",
      101 -> "Switching Protocols",
      200 -> "OK",
      201 -> "Created",
      204 -> "No Content",
      301 -> "Moved Permanently",
      304 -> "Not Modified",
      400 -> "Bad Request",
      401 -> "Unauthorized",
      403 -> "Forbidden",
      404 -> "Not Found",
      405 -> "Method Not Allowed",
      500 -> "Internal Server Error",
    )

  def statusMessageString(code: Int): String = statusMessage.getOrElse(code, code.toString)
