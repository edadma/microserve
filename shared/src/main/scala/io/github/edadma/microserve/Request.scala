package io.github.edadma.microserve

import scala.collection.mutable

class Request(
    val method: String,
    val path: String,
    val url: String,
    val query: Map[String, String],
    val version: String,
    val headers: mutable.Map[String, String],
    val body: Array[Byte],
    val remoteAddress: String,
):
  def get(header: String): Option[String] = headers.get(header)

  def hostname: String = headers.getOrElse("Host", "")

  def bodyString: String = new String(body, "UTF-8")

  override def toString: String =
    s"$method $url HTTP/$version headers=[${headers.mkString(", ")}]"
