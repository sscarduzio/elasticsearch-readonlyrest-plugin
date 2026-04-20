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
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, HCursor, Json, JsonNumber}

import scala.language.implicitConversions


// todo: move?
trait FromStringCreator[A] {
  def creator: String => Either[String, A]
}

trait YamlLeafDecoder[A] extends FromStringCreator[A] {

  def decode(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, A]

  protected def decodeStringOpt(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[String]] = {
    val cursor = json.hcursor
    for {
      oneLine <- scalarAsStringOpt(downOneLineField(cursor, path))
      multiLine <- scalarAsStringOpt(downMultiLineField(cursor, path))
    } yield oneLine.orElse(multiLine)
  }

  private def scalarAsStringOpt(cursor: ACursor): Either[String, Option[String]] =
    cursor.focus match {
      case None => Right(None)
      case Some(j) if j.isNull => Right(None)
      case Some(j) =>
        j.asString.map(s => Right(Some(s)))
          .orElse(j.asBoolean.map(b => Right(Some(b.toString))))
          .orElse(j.asNumber.map(n => Right(Some(numberToString(n)))))
          .getOrElse(Left(s"Expected a scalar value but got: ${j.noSpaces}"))
    }

  private def numberToString(n: JsonNumber): String =
    n.toLong.map(_.toString)
      .orElse(n.toBigDecimal.map(_.bigDecimal.toPlainString))
      .getOrElse(n.toDouble.toString)

  private def downOneLineField(c: HCursor, path: NonEmptyList[NonEmptyString]) = {
    c.downField(toString(path))
  }

  private def downMultiLineField(c: ACursor, path: NonEmptyList[NonEmptyString]) =
    path.foldLeft(c)((cursor, segment) =>
      cursor.downField(segment.value)
    )

  protected def toString(path: NonEmptyList[NonEmptyString]): String = path.toList.mkString(".")
}
object YamlLeafDecoder {

  implicit def toOptionalYamlLeafDecoder[T](implicit decoder: YamlLeafDecoder[T]): YamlLeafDecoder[Option[T]] = {
    new OptionalYamlLeafDecoder
  }

  final def from[T](creator: String => Either[String, T]): YamlLeafDecoder[T] =
    new RequiredYamlLeafDecoder[T](creator)
}

private final class OptionalYamlLeafDecoder[T : YamlLeafDecoder] extends YamlLeafDecoder[Option[T]] {

  override def decode(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] = {
    for {
      value <- decodeStringOpt(json, path)
      result <- value match {
        case Some(value) => creator(value)
        case None => Right(None)
      }
    } yield result
  }

  override def creator: String => Either[String, Option[T]] = { str =>
    implicitly[YamlLeafDecoder[T]]
      .creator(str)
      .map(Some.apply)
  }
}

private final class RequiredYamlLeafDecoder[T](override val creator: String => Either[String, T])
  extends YamlLeafDecoder[T] {

  override def decode(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, T] =  {
    for {
      value <- decodeStringOpt(json, path)
      result <- value match {
        case Some(value) => creator(value)
        case None => Left(s"Cannot find '.${toString(path)}' path")
      }
    } yield result
  }
}