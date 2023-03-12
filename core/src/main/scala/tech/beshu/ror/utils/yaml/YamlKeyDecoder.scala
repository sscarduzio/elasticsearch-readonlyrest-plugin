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
package tech.beshu.ror.utils.yaml

import cats.data.NonEmptyList
import io.circe.Decoder.Result
import io.circe.{ACursor, Decoder, HCursor}

private class YamlKeyDecoder[A: Decoder](segments: NonEmptyList[String], default: A) extends Decoder[A] {
  override def apply(c: HCursor): Result[A] = {
    for {
      oneLine <- downOneLineField(c).as[Option[A]]
      multiLine <- downMultiLineField(c).as[Option[A]]
    } yield {
      oneLine
        .orElse(multiLine)
        .getOrElse(default)
    }
  }

  private def downOneLineField(c: HCursor) = {
    val field = segments.toList.mkString(".")
    c.downField(field)
  }

  private def downMultiLineField(c: ACursor) =
    segments.foldLeft(c)((cursor, segment) =>
      cursor.downField(segment)
    )
}

object YamlKeyDecoder {
  def apply[A: Decoder](segments: NonEmptyList[String], default: A): Decoder[A] = {
    new YamlKeyDecoder[A](segments, default)
  }
}