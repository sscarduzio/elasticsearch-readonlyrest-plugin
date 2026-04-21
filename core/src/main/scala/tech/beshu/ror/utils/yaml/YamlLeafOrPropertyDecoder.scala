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
import cats.{Functor, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, HCursor, Json, JsonNumber}
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName

import scala.language.implicitConversions

trait YamlLeafOrPropertyDecoder[T] {
  def decode(json: Json): Either[String, T]
}

object YamlLeafOrPropertyDecoder {

  def createRequiredValueDecoder[T](path: NonEmptyList[NonEmptyString], creator: String => Either[String, T])
                                   (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[T] = {
    implicit val yamlLeafDecoder: YamlLeafDecoder[T] = YamlLeafDecoder.from(creator)
    implicit val propertyValueDecoder: PropertyValueDecoder[T] = PropertyValueDecoder.from(creator)
    apply(path)
  }

  def createOptionalValueDecoder[T](path: NonEmptyList[NonEmptyString], creator: String => Either[String, T])
                                   (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] = {
    implicit val yamlLeafDecoder: YamlLeafDecoder[T] = YamlLeafDecoder.from(creator)
    implicit val propertyValueDecoder: PropertyValueDecoder[T] = PropertyValueDecoder.from(creator)
    new OptionalYamlLeafOrPropertyDecoder[T](path)
  }

  def createOptionalListValueDecoder[T](path: NonEmptyList[NonEmptyString], itemCreator: String => Either[String, T])
                                       (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[Set[T]]] = {
    new OptionalListYamlLeafOrPropertyDecoder[T](path, itemCreator)
  }

  def sectionPresentDecoder(path: NonEmptyList[NonEmptyString]): YamlLeafOrPropertyDecoder[Boolean] = {
    new SectionPresentYamlLeafOrPropertyDecoder(path)
  }

  def fromEither[T](either: Either[String, T]): YamlLeafOrPropertyDecoder[T] = new YamlLeafOrPropertyDecoder[T] {
    override def decode(json: Json): Either[String, T] = either
  }

  def optionalStringDecoder(path: NonEmptyList[NonEmptyString])
                           (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[String]] = {
    createOptionalValueDecoder(path, str => Right(str))
  }

  def optionalBooleanDecoder(path: NonEmptyList[NonEmptyString])
                            (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[Boolean]] = {
    createOptionalValueDecoder(path, str => str.toLowerCase match {
      case "true" => Right(true)
      case "false" => Right(false)
      case other => Left(s"Cannot convert '$other' to boolean")
    })
  }

  def createLegacyPropertyDecoder[T](legacyKey: NonEmptyString, creator: String => Either[String, T])
                                    (implicit propertiesProvider: PropertiesProvider): YamlLeafOrPropertyDecoder[Option[T]] =
    _ => propertiesProvider.getProperty(PropName(legacyKey)) match {
      case Some(str) => creator(str).map(Some.apply)
      case None      => Right(None)
    }

  def pure[T](value: T): YamlLeafOrPropertyDecoder[T] = {
    PureYamlLeafOrPropertyDecoder(value)
  }

  implicit def apply[T](path: NonEmptyList[NonEmptyString])
                       (implicit yamlLeafDecoder: YamlLeafDecoder[T],
                        propertyValueDecoder: PropertyValueDecoder[T]): YamlLeafOrPropertyDecoder[T] = {
    new RequiredYamlLeafOrPropertyDecoder(path)
  }

  implicit class OptionalDecoderOps[T](val decoder: YamlLeafOrPropertyDecoder[Option[T]]) extends AnyVal {
    def orElse(other: YamlLeafOrPropertyDecoder[Option[T]]): YamlLeafOrPropertyDecoder[Option[T]] =
      json => decoder.decode(json).flatMap {
        case some @ Some(_) => Right(some)
        case None           => other.decode(json)
      }
  }

  implicit val yamlLeafOrPropertyDecoderFunctor: Functor[YamlLeafOrPropertyDecoder] = new Functor {
    override def map[A, B](fa: YamlLeafOrPropertyDecoder[A])(f: A => B): YamlLeafOrPropertyDecoder[B] = {
      (json: Json) => fa.decode(json).map(f)
    }
  }

  implicit val yamlLeafOrPropertyDecoderMonad: Monad[YamlLeafOrPropertyDecoder] = new Monad {

    override def pure[A](x: A): YamlLeafOrPropertyDecoder[A] = new YamlLeafOrPropertyDecoder {
      override def decode(json: Json): Either[String, A] = Right(x)
    }

    override def flatMap[A, B](fa: YamlLeafOrPropertyDecoder[A])
                              (f: A => YamlLeafOrPropertyDecoder[B]): YamlLeafOrPropertyDecoder[B] = {
      (json: Json) => {
        for {
          value <- fa.decode(json)
          result <- f(value).decode(json)
        } yield result
      }
    }

    override def tailRecM[A, B](a: A)(f: A => YamlLeafOrPropertyDecoder[Either[A, B]]): YamlLeafOrPropertyDecoder[B] =
      json => {
        @scala.annotation.tailrec
        def loop(current: A): Either[String, B] =
          f(current).decode(json) match {
            case Left(err)           => Left(err)
            case Right(Left(nextA))  => loop(nextA)
            case Right(Right(b))     => Right(b)
          }
        loop(a)
      }
  }
}

private trait YamlLeafDecoder[A] {
  def creator: String => Either[String, A]
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

  private def downOneLineField(c: HCursor, path: NonEmptyList[NonEmptyString]) =
    c.downField(path.toList.mkString("."))

  private def downMultiLineField(c: ACursor, path: NonEmptyList[NonEmptyString]) =
    path.foldLeft(c)((cursor, segment) => cursor.downField(segment.value))

  protected def pathAsString(path: NonEmptyList[NonEmptyString]): String = path.toList.mkString(".")
}

private object YamlLeafDecoder {
  implicit def toOptionalYamlLeafDecoder[T](implicit decoder: YamlLeafDecoder[T]): YamlLeafDecoder[Option[T]] =
    new OptionalYamlLeafDecoder

  def from[T](creator: String => Either[String, T]): YamlLeafDecoder[T] =
    new RequiredYamlLeafDecoder[T](creator)
}

private final class OptionalYamlLeafDecoder[T: YamlLeafDecoder] extends YamlLeafDecoder[Option[T]] {

  override def decode(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] = {
    for {
      value <- decodeStringOpt(json, path)
      result <- value match {
        case Some(v) => creator(v)
        case None    => Right(None)
      }
    } yield result
  }

  override def creator: String => Either[String, Option[T]] = { str =>
    implicitly[YamlLeafDecoder[T]].creator(str).map(Some.apply)
  }
}

private final class RequiredYamlLeafDecoder[T](override val creator: String => Either[String, T])
  extends YamlLeafDecoder[T] {

  override def decode(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, T] = {
    for {
      value <- decodeStringOpt(json, path)
      result <- value match {
        case Some(v) => creator(v)
        case None    => Left(s"Cannot find '.${pathAsString(path)}' path")
      }
    } yield result
  }
}

private trait PropertyValueDecoder[T] {
  def creator: String => Either[String, T]
  protected implicit def provider: PropertiesProvider
  def decode(propertyName: NonEmptyString): Either[String, T]
}

private object PropertyValueDecoder {

  implicit def toOptionPropertyValueDecoder[T](implicit decoder: PropertyValueDecoder[T]): PropertyValueDecoder[Option[T]] =
    new OptionalPropertyValueDecoder()

  def from[T](creator: String => Either[String, T])
             (implicit provider: PropertiesProvider): PropertyValueDecoder[T] =
    new RequiredPropertyValueDecoder[T](creator)

  private final class OptionalPropertyValueDecoder[T: PropertyValueDecoder] extends PropertyValueDecoder[Option[T]] {

    override protected implicit def provider: PropertiesProvider =
      implicitly[PropertyValueDecoder[T]].provider

    override def creator: String => Either[String, Option[T]] = { str =>
      implicitly[PropertyValueDecoder[T]].creator(str).map(Some.apply)
    }

    override def decode(propertyName: NonEmptyString): Either[String, Option[T]] = {
      provider.getProperty(PropName(propertyName)) match {
        case Some(str) => creator(str)
        case None      => Right(None)
      }
    }
  }

  private final class RequiredPropertyValueDecoder[T](val creator: String => Either[String, T])
                                                     (implicit val provider: PropertiesProvider)
    extends PropertyValueDecoder[T] {

    override def decode(propertyName: NonEmptyString): Either[String, T] = {
      for {
        propertyValue <- provider.getProperty(PropName(propertyName)).toRight(s"Cannot find property with name '$propertyName'")
        value <- creator(propertyValue)
      } yield value
    }
  }
}

private final class OptionalYamlLeafOrPropertyDecoder[T: YamlLeafDecoder : PropertyValueDecoder](path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[Option[T]] {

  override def decode(json: Json): Either[String, Option[T]] = {
    for {
      result <- decodeUsingOptionalYamlKeyDecoder(json, path)
      finalResult <- result match {
        case Some(value) => Right(Some(value))
        case None        => decodeUsingOptionalPropertiesValueDecoder(path)
      }
    } yield finalResult
  }

  private def decodeUsingOptionalYamlKeyDecoder(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] =
    implicitly[YamlLeafDecoder[Option[T]]].decode(json, path)

  private def decodeUsingOptionalPropertiesValueDecoder(path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] = {
    val pathString = NonEmptyString.unsafeFrom(path.toList.mkString("."))
    implicitly[PropertyValueDecoder[Option[T]]].decode(pathString)
  }
}

private final class RequiredYamlLeafOrPropertyDecoder[T: YamlLeafDecoder : PropertyValueDecoder](path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[T] {

  private val optionalDecoder = new OptionalYamlLeafOrPropertyDecoder[T](path)

  override def decode(json: Json): Either[String, T] = {
    optionalDecoder
      .decode(json)
      .flatMap {
        case None        => Left(s"Required setting not found at path '${path.toList.map(_.value).mkString(".")}'")
        case Some(value) => Right(value)
      }
  }
}

private final case class PureYamlLeafOrPropertyDecoder[T](value: T) extends YamlLeafOrPropertyDecoder[T] {
  override def decode(json: Json): Either[String, T] = Right(value)
}

private final class OptionalListYamlLeafOrPropertyDecoder[T](path: NonEmptyList[NonEmptyString],
                                                             itemCreator: String => Either[String, T])
                                                            (implicit propertiesProvider: PropertiesProvider)
  extends YamlLeafOrPropertyDecoder[Option[Set[T]]] {

  override def decode(json: Json): Either[String, Option[Set[T]]] = {
    decodeFromYaml(json).flatMap {
      case Some(values) => Right(Some(values))
      case None         => decodeFromProperty()
    }
  }

  private def decodeFromYaml(json: Json): Either[String, Option[Set[T]]] = {
    val cursor = json.hcursor
    val oneLineFocus = cursor.downField(path.toList.map(_.value).mkString(".")).focus
    val multiLineFocus = path.foldLeft[ACursor](cursor)((c, segment) => c.downField(segment.value)).focus
    oneLineFocus.orElse(multiLineFocus) match {
      case None                   => Right(None)
      case Some(j) if j.isNull    => Right(None)
      case Some(j) if j.isArray   =>
        j.asArray.get.toList.foldLeft[Either[String, List[T]]](Right(List.empty)) { case (acc, elem) =>
          for {
            xs  <- acc
            str <- elem.asString.toRight(s"Expected string element at path '.${path.toList.map(_.value).mkString(".")}', got ${elem.noSpaces}")
            v   <- itemCreator(str)
          } yield xs :+ v
        }.map(xs => Some(xs.toSet))
      case Some(j) if j.isString  =>
        parseCommaSeparated(j.asString.get).map(Some.apply)
      case Some(j)                =>
        Left(s"Expected list or string at path '.${path.toList.map(_.value).mkString(".")}', got ${j.noSpaces}")
    }
  }

  private def decodeFromProperty(): Either[String, Option[Set[T]]] = {
    val propName = NonEmptyString.unsafeFrom(path.toList.map(_.value).mkString("."))
    propertiesProvider.getProperty(PropName(propName)) match {
      case Some(str) => parseCommaSeparated(str).map(Some.apply)
      case None      => Right(None)
    }
  }

  private def parseCommaSeparated(str: String): Either[String, Set[T]] = {
    str.split(",").toList
      .map(_.trim).filter(_.nonEmpty)
      .foldLeft[Either[String, Set[T]]](Right(Set.empty)) {
        case (Left(e), _)   => Left(e)
        case (Right(acc), s) => itemCreator(s).map(acc + _)
      }
  }
}

private final class SectionPresentYamlLeafOrPropertyDecoder(path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[Boolean] {

  override def decode(json: Json): Either[String, Boolean] = {
    val cursor = json.hcursor
    val oneLineFocus = cursor.downField(path.toList.map(_.value).mkString(".")).focus
    val multiLineFocus = path.foldLeft[ACursor](cursor)((c, segment) => c.downField(segment.value)).focus
    Right(oneLineFocus.orElse(multiLineFocus).exists(j => !j.isNull))
  }
}
