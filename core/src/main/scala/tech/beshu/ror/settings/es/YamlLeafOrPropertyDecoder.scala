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
package tech.beshu.ror.settings.es

import cats.data.NonEmptyList
import cats.{Functor, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ACursor, Json}
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.yaml.YamlLeafDecoder

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
    apply(path)
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

  def pure[T](value: T): YamlLeafOrPropertyDecoder[T] = {
    PureYamlLeafOrPropertyDecoder(value)
  }

  implicit def apply[T](path: NonEmptyList[NonEmptyString])
                       (implicit yamlLeafDecoder: YamlLeafDecoder[T],
                        propertyValueDecoder: PropertyValueDecoder[T]): YamlLeafOrPropertyDecoder[T] = {
    new RequiredYamlLeafOrPropertyDecoder(path)
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
      ???
  }
}

final class OptionalYamlLeafOrPropertyDecoder[T: YamlLeafDecoder : PropertyValueDecoder](path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[Option[T]] {

  override def decode(json: Json): Either[String, Option[T]] = {
    for {
      result <- decodeUsingOptionalYamlKeyDecoder(json, path)
      finalResult <- result match {
        case Some(value) => Right(Some(value))
        case None => decodeUsingOptionalPropertiesValueDecoder(path)
      }
    } yield finalResult
  }

  private def decodeUsingOptionalYamlKeyDecoder(json: Json, path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] = {
    implicitly[YamlLeafDecoder[Option[T]]].decode(json, path)
  }

  private def decodeUsingOptionalPropertiesValueDecoder(path: NonEmptyList[NonEmptyString]): Either[String, Option[T]] = {
    val pathString = NonEmptyString.unsafeFrom(path.toList.mkString("."))
    implicitly[PropertyValueDecoder[Option[T]]].decode(pathString)
  }
}

final class RequiredYamlLeafOrPropertyDecoder[T: YamlLeafDecoder : PropertyValueDecoder](path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[T] {

  private val optionalDecoder = new OptionalYamlLeafOrPropertyDecoder[T](path)

  override def decode(json: Json): Either[String, T] = {
    optionalDecoder
      .decode(json)
      .flatMap {
        case None => Left(???)
        case Some(value) => Right(value)
      }
  }

}

final case class PureYamlLeafOrPropertyDecoder[T](value: T) extends YamlLeafOrPropertyDecoder[T] {
  override def decode(json: Json): Either[String, T] = Right(value)
}

final class OptionalListYamlLeafOrPropertyDecoder[T](path: NonEmptyList[NonEmptyString],
                                                     itemCreator: String => Either[String, T])
                                                    (implicit propertiesProvider: PropertiesProvider)
  extends YamlLeafOrPropertyDecoder[Option[Set[T]]] {

  override def decode(json: Json): Either[String, Option[Set[T]]] = {
    decodeFromYaml(json).flatMap {
      case Some(values) => Right(Some(values))
      case None => decodeFromProperty()
    }
  }

  private def decodeFromYaml(json: Json): Either[String, Option[Set[T]]] = {
    val cursor = json.hcursor
    val oneLineFocus = cursor.downField(path.toList.map(_.value).mkString(".")).focus
    val multiLineFocus = path.foldLeft[ACursor](cursor)((c, segment) => c.downField(segment.value)).focus
    oneLineFocus.orElse(multiLineFocus) match {
      case None => Right(None)
      case Some(j) if j.isNull => Right(None)
      case Some(j) if j.isArray =>
        j.asArray.get.toList.foldLeft[Either[String, List[T]]](Right(List.empty)) { case (acc, elem) =>
          for {
            xs <- acc
            str <- elem.asString.toRight(s"Expected string element at path '.${path.toList.map(_.value).mkString(".")}', got ${elem.noSpaces}")
            value <- itemCreator(str)
          } yield xs :+ value
        }.map(xs => Some(xs.toSet))
      case Some(j) if j.isString =>
        parseCommaSeparated(j.asString.get).map(Some.apply)
      case Some(j) =>
        Left(s"Expected list or string at path '.${path.toList.map(_.value).mkString(".")}', got ${j.noSpaces}")
    }
  }

  private def decodeFromProperty(): Either[String, Option[Set[T]]] = {
    val propName = NonEmptyString.unsafeFrom(path.toList.map(_.value).mkString("."))
    propertiesProvider.getProperty(PropName(propName)) match {
      case Some(str) => parseCommaSeparated(str).map(Some.apply)
      case None => Right(None)
    }
  }

  private def parseCommaSeparated(str: String): Either[String, Set[T]] = {
    str.split(",").toList
      .map(_.trim).filter(_.nonEmpty)
      .foldLeft[Either[String, Set[T]]](Right(Set.empty)) {
        case (Left(e), _) => Left(e)
        case (Right(acc), s) => itemCreator(s).map(acc + _)
      }
  }
}

final class SectionPresentYamlLeafOrPropertyDecoder(path: NonEmptyList[NonEmptyString])
  extends YamlLeafOrPropertyDecoder[Boolean] {

  override def decode(json: Json): Either[String, Boolean] = {
    val cursor = json.hcursor
    val oneLineFocus = cursor.downField(path.toList.map(_.value).mkString(".")).focus
    val multiLineFocus = path.foldLeft[ACursor](cursor)((c, segment) => c.downField(segment.value)).focus
    Right(oneLineFocus.orElse(multiLineFocus).exists(j => !j.isNull))
  }
}