/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.utils

import ujson as originalUjson
import ujson.{Obj as ujsonObj, Value as ujsonValue}

object TestUjson {

  object ujson {
    type Value = ujsonValue

    type Obj = ujsonObj

    def read(s: String, trace: Boolean = false): Value = originalUjson.read(s.replaceAll("\r\n", "\n"), trace)

    def write(t: Value,
              indent: Int = -1,
              escapeUnicode: Boolean = false,
              sortKeys: Boolean = false): String = originalUjson.write(t, indent, escapeUnicode, sortKeys)
  }

}
