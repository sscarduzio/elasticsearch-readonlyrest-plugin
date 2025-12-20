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

private class YamlKeyDecoder[A: Decoder](segments: NonEmptyList[String]) extends Decoder[Option[A]] {
  override def apply(c: HCursor): Result[Option[A]] = {
    for {
      oneLine <- downOneLineField(c).as[Option[A]]
      multiLine <- downMultiLineField(c).as[Option[A]]
    } yield {
      oneLine.orElse(multiLine)
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
  def apply[A: Decoder](path: NonEmptyList[String], default: A): Decoder[A] = {
    apply(path).map(_.getOrElse(default))
  }

  def apply[A: Decoder](path: NonEmptyList[String], alternativePath: NonEmptyList[String], default: A): Decoder[A] = {
    for {
      decodedValue <- apply(path)
      alternativeDecodedValue <- decodedValue match {
        case Some(value) => Decoder.const[Option[A]](Some(value))
        case None => apply(alternativePath)
      }
    } yield alternativeDecodedValue.getOrElse(default)
  }

  def apply[A: Decoder](path: NonEmptyList[String]): Decoder[Option[A]] = {
    new YamlKeyDecoder[A](path)
  }
}