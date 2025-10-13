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
import io.circe.Json
import tech.beshu.ror.utils.yaml.YamlLeafDecoder
import scala.language.implicitConversions

trait YamlLeafOrPropertyDecoder[T] {
  def decode(json: Json): Either[String, T]
}
object YamlLeafOrPropertyDecoder {

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
