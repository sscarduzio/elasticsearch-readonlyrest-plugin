package tech.beshu.ror.utils

import ujson as ujsonReader
import ujson.Value

object JsonReader {
  def ujsonRead(s: String, trace: Boolean = false): Value.Value = ujsonReader.read(s.replaceAll("\r\n", "\n"), trace)
}
