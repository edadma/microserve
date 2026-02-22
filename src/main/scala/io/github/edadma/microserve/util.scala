package io.github.edadma.microserve

import java.nio.ByteBuffer
import scala.collection.mutable.ArrayBuffer

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

    java.nio.charset.StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes.toArray)).toString
