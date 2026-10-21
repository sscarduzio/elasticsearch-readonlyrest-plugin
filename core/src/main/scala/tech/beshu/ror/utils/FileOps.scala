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

import better.files.File

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction.REPORT
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.control.Exception.catching

object FileOps {

  private val utf8ByteOrderMark = "\uFEFF"

  extension (file: File)

    // Strict: a byte that is not valid UTF-8 is an error, not a U+FFFD replacement character.
    // A UTF-8 byte order mark at the start is removed, because Windows editors write one.
    def contentAsUtf8String: Either[String, String] = {
      val decoder = UTF_8.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)
      catching(classOf[CharacterCodingException])
        .either(decoder.decode(ByteBuffer.wrap(file.byteArray)).toString)
        .left
        .map(_ => s"The file ${file.pathAsString} is not valid UTF-8")
        .map(_.stripPrefix(utf8ByteOrderMark))
    }

}
