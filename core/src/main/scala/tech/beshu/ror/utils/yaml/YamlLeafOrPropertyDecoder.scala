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

import cats.Monad
import cats.data.NonEmptyList
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, Json, JsonNumber}
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.FromString

import scala.language.implicitConversions

trait YamlLeafOrPropertyDecoder[T] {
  def decode(json: Json): Either[String, T]
}

object YamlLeafOrPropertyDecoder {

  def createRequiredValueDecoder[T](path: NonEmptyList[NonEmptyString], decoder: FromString[T])
                                   (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[T] = {
    implicit val valueCreator: FromString[T] = decoder
    apply(path)
  }

  def createOptionalValueDecoder[T](path: NonEmptyList[NonEmptyString], decoder: FromString[T])
                                   (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] = {
    implicit val valueCreator: FromString[T] = decoder
    applyOptional(path)
  }

  def createOptionalListValueDecoder[T](path: NonEmptyList[NonEmptyString], itemDecoder: FromString[T])
                                       (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[Set[T]]] = {
    implicit val valueCreator: FromString[T] = itemDecoder
    new OptionalListYamlLeafOrPropertyDecoder[T](path)
  }

  def whenSectionPresent[T](sectionPath: NonEmptyList[NonEmptyString])
                           (inner: YamlLeafOrPropertyDecoder[Option[T]])
                           (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] =
    json => {
      val inYaml = JsonPathOps.focusAt(json, sectionPath).exists(j => !j.isNull)
      val inProperties = propertiesProvider.hasPropertyWithPrefix(JsonPathOps.pathAsString(sectionPath))
      if (inYaml || inProperties)
        inner.decode(json)
      else
        Right(None)
    }

  def fromEither[T](either: Either[String, T]): YamlLeafOrPropertyDecoder[T] =
    _ => either

  def optionalStringDecoder(path: NonEmptyList[NonEmptyString])
                           (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[String]] =
    createOptionalValueDecoder(path, FromString.string)

  def optionalBooleanDecoder(path: NonEmptyList[NonEmptyString])
                            (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[Boolean]] =
    createOptionalValueDecoder(path, FromString.boolean)

  def createLegacyPropertyDecoder[T](legacyKey: NonEmptyString, decoder: FromString[T])
                                    (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] =
    _ => propertiesProvider.getProperty(PropName(legacyKey)) match {
      case Some(str) => decoder.decode(str).map(Some.apply)
      case None      => Right(None)
    }

  def pure[T](value: T): YamlLeafOrPropertyDecoder[T] =
    _ => Right(value)

  implicit def apply[T](path: NonEmptyList[NonEmptyString])
                       (implicit valueCreator: FromString[T],
                        propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[T] =
    new RequiredYamlLeafOrPropertyDecoder(path)

  implicit def applyOptional[T](path: NonEmptyList[NonEmptyString])
                               (implicit valueCreator: FromString[T],
                                propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] =
    new OptionalYamlLeafOrPropertyDecoder[T](path)

  implicit class OptionalDecoderOps[T](val decoder: YamlLeafOrPropertyDecoder[Option[T]]) extends AnyVal {
    def orElse(other: YamlLeafOrPropertyDecoder[Option[T]]): YamlLeafOrPropertyDecoder[Option[T]] =
      json => decoder.decode(json).flatMap {
        case some @ Some(_) => Right(some)
        case None           => other.decode(json)
      }
  }

  implicit val yamlLeafOrPropertyDecoderMonad: Monad[YamlLeafOrPropertyDecoder] = new Monad[YamlLeafOrPropertyDecoder] {
    override def pure[A](x: A): YamlLeafOrPropertyDecoder[A] =
      YamlLeafOrPropertyDecoder.pure(x)

    override def flatMap[A, B](fa: YamlLeafOrPropertyDecoder[A])
                              (f: A => YamlLeafOrPropertyDecoder[B]): YamlLeafOrPropertyDecoder[B] =
      json => fa.decode(json).flatMap(value => f(value).decode(json))

    override def tailRecM[A, B](a: A)(f: A => YamlLeafOrPropertyDecoder[Either[A, B]]): YamlLeafOrPropertyDecoder[B] =
      json => {
        @scala.annotation.tailrec
        def loop(current: A): Either[String, B] =
          f(current).decode(json) match {
            case Left(err)          => Left(err)
            case Right(Left(nextA)) => loop(nextA)
            case Right(Right(b))    => Right(b)
          }
        loop(a)
      }
  }
}

private object JsonPathOps {

  def pathAsString(path: NonEmptyList[NonEmptyString]): String =
    path.toList.map(_.value).mkString(".")

  def focusAt(json: Json, path: NonEmptyList[NonEmptyString]): Option[Json] = {
    val cursor = json.hcursor
    cursor.downField(pathAsString(path)).focus
      .orElse(path.foldLeft[ACursor](cursor)((c, segment) => c.downField(segment.value)).focus)
  }

  def existsAt(json: Json, path: NonEmptyList[NonEmptyString]): Boolean =
    focusAt(json, path).exists(j => !j.isNull)

  def scalarAsStringOpt(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[String]] =
    focusAt(json, path) match {
      case None                => Right(None)
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
}

private final class OptionalYamlLeafOrPropertyDecoder[T](path: NonEmptyList[NonEmptyString])
                                                        (implicit valueCreator: FromString[T],
                                                         propertiesProvider: PropertiesProvider)
  extends YamlLeafOrPropertyDecoder[Option[T]] {

  private val propName = NonEmptyString.unsafeFrom(JsonPathOps.pathAsString(path))

  override def decode(json: Json): Either[String, Option[T]] = {
    val inYaml = JsonPathOps.existsAt(json, path)
    val inProperty = propertiesProvider.getProperty(PropName(propName)).isDefined
    (inYaml, inProperty) match {
      case (true, true) => Left(conflictError)
      case (true, false) => decodeFromYaml(json)
      case (false, true) => decodeFromProperty()
      case (false, false) => Right(None)
    }
  }

  private def decodeFromYaml(json: Json): Either[String, Option[T]] =
    JsonPathOps.scalarAsStringOpt(json, path).flatMap {
      case Some(str) => valueCreator.decode(str).map(Some.apply).left.map(withPath)
      case None      => Right(None)
    }

  private def decodeFromProperty(): Either[String, Option[T]] =
    propertiesProvider.getProperty(PropName(propName)) match {
      case Some(str) => valueCreator.decode(str).map(Some.apply).left.map(withPath)
      case None      => Right(None)
    }

  private def withPath(err: String): String =
    s"Invalid value at '.$propName': $err"

  private def conflictError: String =
    s"Value at '.$propName' is defined in both YAML configuration and system properties. Please use only one."
}

private final class RequiredYamlLeafOrPropertyDecoder[T](path: NonEmptyList[NonEmptyString])
                                                        (implicit valueCreator: FromString[T],
                                                         propertiesProvider: PropertiesProvider)
  extends YamlLeafOrPropertyDecoder[T] {

  private val optionalDecoder = new OptionalYamlLeafOrPropertyDecoder[T](path)

  override def decode(json: Json): Either[String, T] =
    optionalDecoder.decode(json).flatMap {
      case Some(value) => Right(value)
      case None        => Left(s"Cannot find '.${JsonPathOps.pathAsString(path)}' path")
    }
}

private final class OptionalListYamlLeafOrPropertyDecoder[T](path: NonEmptyList[NonEmptyString])
                                                            (implicit valueCreator: FromString[T],
                                                             propertiesProvider: PropertiesProvider)
  extends YamlLeafOrPropertyDecoder[Option[Set[T]]] {

  private val propName = NonEmptyString.unsafeFrom(JsonPathOps.pathAsString(path))

  override def decode(json: Json): Either[String, Option[Set[T]]] = {
    val inYaml = JsonPathOps.existsAt(json, path)
    val inProperty = propertiesProvider.getProperty(PropName(propName)).isDefined
    (inYaml, inProperty) match {
      case (true, true)  => Left(conflictError)
      case (true, false) => decodeFromYaml(json)
      case (false, true) => decodeFromProperty()
      case (false, false) => Right(None)
    }
  }

  private def decodeFromYaml(json: Json): Either[String, Option[Set[T]]] =
    JsonPathOps.focusAt(json, path) match {
      case None                => Right(None)
      case Some(j) if j.isNull => Right(None)
      case Some(j) =>
        j.asArray.map(decodeArray)
          .orElse(j.asString.map(str => parseCommaSeparated(str).map(Some.apply)))
          .getOrElse(Left(s"Expected list or string at path '.$propName', got ${j.noSpaces}"))
    }

  private def decodeArray(arr: Vector[Json]): Either[String, Option[Set[T]]] =
    arr.toList.traverse { elem =>
      elem.asString
        .toRight(s"Expected string element at path '.$propName', got ${elem.noSpaces}")
        .flatMap(valueCreator.decode)
    }.map(xs => Some(xs.toSet))

  private def decodeFromProperty(): Either[String, Option[Set[T]]] =
    propertiesProvider.getProperty(PropName(propName)) match {
      case Some(str) => parseCommaSeparated(str).map(Some.apply)
      case None      => Right(None)
    }

  private def parseCommaSeparated(str: String): Either[String, Set[T]] =
    str.split(",").toList
      .map(_.trim).filter(_.nonEmpty)
      .traverse(valueCreator.decode)
      .map(_.toSet)

  private def conflictError: String =
    s"Value at '.$propName' is defined in both YAML configuration and system properties. Please use only one."
}
