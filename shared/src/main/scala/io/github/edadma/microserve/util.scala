package io.github.edadma.microserve

import scala.collection.mutable.ArrayBuffer

/** Decode a percent-encoded URL component. Cross-platform: relies only on
  * `new String(Array[Byte], "UTF-8")`, which JVM, Scala.js, and Scala Native all support.
  */
def urlDecode(s: String): String =
  if s.indexOf('%') == -1 && s.indexOf('+') == -1 then s
  else
    val bytes = new ArrayBuffer[Byte]
    var idx = 0

    def hex(d: Char): Int =
      if '0' <= d && d <= '9' then d - '0'
      else if 'A' <= d && d <= 'F' then d - 'A' + 10
      else if 'a' <= d && d <= 'f' then d - 'a' + 10
      else sys.error(s"invalid hex digit")

    while idx < s.length do
      s(idx) match
        case '%' =>
          if idx + 2 >= s.length then sys.error("truncated percent-encoding")
          bytes += ((hex(s(idx + 1)) << 4) + hex(s(idx + 2))).toByte
          idx += 2
        case '+' => bytes += ' '.toByte
        case c   => bytes += c.toByte
      idx += 1

    new String(bytes.toArray, "UTF-8")
