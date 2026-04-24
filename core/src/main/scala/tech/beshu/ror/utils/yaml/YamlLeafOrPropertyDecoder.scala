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
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.FromString

import scala.language.implicitConversions

trait YamlLeafOrPropertyOrEnvDecoder[T] {
  def decode(json: Json): Either[String, T]
}

object YamlLeafOrPropertyOrEnvDecoder {

  def createRequiredValueDecoder[T](path: NonEmptyList[NonEmptyString], decoder: FromString[T])
                                   (implicit propertiesProvider: PropertiesProvider,
                                    envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[T] = {
    implicit val valueCreator: FromString[T] = decoder
    apply(path)
  }

  def createOptionalValueDecoder[T](path: NonEmptyList[NonEmptyString], decoder: FromString[T])
                                   (implicit propertiesProvider: PropertiesProvider,
                                    envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[T]] = {
    implicit val valueCreator: FromString[T] = decoder
    applyOptional(path)
  }

  def createOptionalListValueDecoder[T](path: NonEmptyList[NonEmptyString], itemDecoder: FromString[T])
                                       (implicit propertiesProvider: PropertiesProvider,
                                        envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[Set[T]]] = {
    implicit val valueCreator: FromString[T] = itemDecoder
    new OptionalListYamlLeafOrPropertyOrEnvDecoder[T](path)
  }

  def whenSectionPresent[T](sectionPath: NonEmptyList[NonEmptyString])
                           (inner: YamlLeafOrPropertyOrEnvDecoder[Option[T]])
                           (implicit propertiesProvider: PropertiesProvider,
                            envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[T]] =
    json => {
      val inYaml       = JsonPathOps.focusAt(json, sectionPath).exists(j => !j.isNull)
      val inProperties = propertiesProvider.hasPropertyWithPrefix(JsonPathOps.pathAsString(sectionPath) + ".")
      val envPrefix    = JsonPathOps.pathPrefixToEnvVarPrefix(sectionPath)
      val inEnv        = envVarsProvider.hasEnvMatching(k => k.startsWith(envPrefix) && (k.length == envPrefix.length || k.charAt(envPrefix.length) != '_'))
      if (inYaml || inProperties || inEnv)
        inner.decode(json)
      else
        Right(None)
    }

  def fromEither[T](either: Either[String, T]): YamlLeafOrPropertyOrEnvDecoder[T] =
    _ => either

  def optionalStringDecoder(path: NonEmptyList[NonEmptyString])
                           (implicit propertiesProvider: PropertiesProvider,
                            envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[String]] =
    createOptionalValueDecoder(path, FromString.string)

  def optionalBooleanDecoder(path: NonEmptyList[NonEmptyString])
                            (implicit propertiesProvider: PropertiesProvider,
                             envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[Boolean]] =
    createOptionalValueDecoder(path, FromString.boolean)

  def createLegacyPropertyDecoder[T](legacyKey: NonEmptyString, decoder: FromString[T])
                                    (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyOrEnvDecoder[Option[T]] =
    _ => propertiesProvider.getProperty(PropName(legacyKey)) match {
      case Some(str) => decoder.decode(str).map(Some.apply)
      case None      => Right(None)
    }

  def pure[T](value: T): YamlLeafOrPropertyOrEnvDecoder[T] =
    _ => Right(value)

  implicit def apply[T](path: NonEmptyList[NonEmptyString])
                       (implicit valueCreator: FromString[T],
                        propertiesProvider: PropertiesProvider,
                        envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[T] =
    new RequiredYamlLeafOrPropertyOrEnvDecoder(path)

  implicit def applyOptional[T](path: NonEmptyList[NonEmptyString])
                               (implicit valueCreator: FromString[T],
                                propertiesProvider: PropertiesProvider,
                                envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[T]] =
    new OptionalYamlLeafOrPropertyOrEnvDecoder[T](path)

  implicit class OptionalDecoderOps[T](val decoder: YamlLeafOrPropertyOrEnvDecoder[Option[T]]) extends AnyVal {
    def orElse(other: YamlLeafOrPropertyOrEnvDecoder[Option[T]]): YamlLeafOrPropertyOrEnvDecoder[Option[T]] =
      json => decoder.decode(json).flatMap {
        case some @ Some(_) => Right(some)
        case None           => other.decode(json)
      }
  }

  implicit val monad: Monad[YamlLeafOrPropertyOrEnvDecoder] = new Monad[YamlLeafOrPropertyOrEnvDecoder] {
    override def pure[A](x: A): YamlLeafOrPropertyOrEnvDecoder[A] =
      YamlLeafOrPropertyOrEnvDecoder.pure(x)

    override def flatMap[A, B](fa: YamlLeafOrPropertyOrEnvDecoder[A])
                              (f: A => YamlLeafOrPropertyOrEnvDecoder[B]): YamlLeafOrPropertyOrEnvDecoder[B] =
      json => fa.decode(json).flatMap(value => f(value).decode(json))

    override def tailRecM[A, B](a: A)(f: A => YamlLeafOrPropertyOrEnvDecoder[Either[A, B]]): YamlLeafOrPropertyOrEnvDecoder[B] =
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

object JsonPathOps {

  def pathAsString(path: NonEmptyList[NonEmptyString]): String =
    path.toList.map(_.value).mkString(".")

  def pathToEnvVarName(path: NonEmptyList[NonEmptyString]): String =
    "ES_SETTING_" + path.toList.map(seg => seg.value.replace("_", "__").toUpperCase).mkString("_")

  def pathPrefixToEnvVarPrefix(path: NonEmptyList[NonEmptyString]): String =
    pathToEnvVarName(path) + "_"

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

private final class OptionalYamlLeafOrPropertyOrEnvDecoder[T](path: NonEmptyList[NonEmptyString])
                                                             (implicit valueCreator: FromString[T],
                                                              propertiesProvider: PropertiesProvider,
                                                              envVarsProvider: EnvVarsProvider)
  extends YamlLeafOrPropertyOrEnvDecoder[Option[T]] {

  private val propName   = NonEmptyString.unsafeFrom(JsonPathOps.pathAsString(path))
  private val envVarName = JsonPathOps.pathToEnvVarName(path)

  override def decode(json: Json): Either[String, Option[T]] = {
    val inYaml     = JsonPathOps.existsAt(json, path)
    val inProperty = propertiesProvider.getProperty(PropName(propName)).isDefined
    val inEnv      = envVarsProvider.getEnv(EnvVarName(NonEmptyString.unsafeFrom(envVarName))).isDefined
    (inYaml, inProperty, inEnv) match {
      case (true, _, _)          => decodeFromYaml(json)
      case (false, true, _)      => decodeFromProperty()
      case (false, false, true)  => decodeFromEnv()
      case (false, false, false) => Right(None)
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

  private def decodeFromEnv(): Either[String, Option[T]] =
    envVarsProvider.getEnv(EnvVarName(NonEmptyString.unsafeFrom(envVarName))) match {
      case Some(str) => valueCreator.decode(str).map(Some.apply).left.map(withPath)
      case None      => Right(None)
    }

  private def withPath(err: String): String =
    s"Invalid value at '.$propName': $err"
}

private final class RequiredYamlLeafOrPropertyOrEnvDecoder[T](path: NonEmptyList[NonEmptyString])
                                                             (implicit valueCreator: FromString[T],
                                                              propertiesProvider: PropertiesProvider,
                                                              envVarsProvider: EnvVarsProvider)
  extends YamlLeafOrPropertyOrEnvDecoder[T] {

  private val optionalDecoder = new OptionalYamlLeafOrPropertyOrEnvDecoder[T](path)

  override def decode(json: Json): Either[String, T] =
    optionalDecoder.decode(json).flatMap {
      case Some(value) => Right(value)
      case None        => Left(s"Cannot find '.${JsonPathOps.pathAsString(path)}' path")
    }
}

private final class OptionalListYamlLeafOrPropertyOrEnvDecoder[T](path: NonEmptyList[NonEmptyString])
                                                                 (implicit valueCreator: FromString[T],
                                                                  propertiesProvider: PropertiesProvider,
                                                                  envVarsProvider: EnvVarsProvider)
  extends YamlLeafOrPropertyOrEnvDecoder[Option[Set[T]]] {

  private val propName   = NonEmptyString.unsafeFrom(JsonPathOps.pathAsString(path))
  private val envVarName = JsonPathOps.pathToEnvVarName(path)

  override def decode(json: Json): Either[String, Option[Set[T]]] = {
    val inYaml     = JsonPathOps.existsAt(json, path)
    val inProperty = propertiesProvider.getProperty(PropName(propName)).isDefined
    val inEnv      = envVarsProvider.getEnv(EnvVarName(NonEmptyString.unsafeFrom(envVarName))).isDefined
    (inYaml, inProperty, inEnv) match {
      case (true, _, _)          => decodeFromYaml(json)
      case (false, true, _)      => decodeFromProperty()
      case (false, false, true)  => decodeFromEnv()
      case (false, false, false) => Right(None)
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

  private def decodeFromEnv(): Either[String, Option[Set[T]]] =
    envVarsProvider.getEnv(EnvVarName(NonEmptyString.unsafeFrom(envVarName))) match {
      case Some(str) => parseCommaSeparated(str).map(Some.apply)
      case None      => Right(None)
    }

  private def parseCommaSeparated(str: String): Either[String, Set[T]] =
    str.split(",").toList
      .map(_.trim).filter(_.nonEmpty)
      .traverse(valueCreator.decode)
      .map(_.toSet)
}
