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

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.yaml.FromStringCreator

trait PropertyValueDecoder[T] extends FromStringCreator[T] {

  protected [PropertyValueDecoder] implicit def provider: PropertiesProvider

  def decode(propertyName: NonEmptyString): Either[String, T]
}
object PropertyValueDecoder {

  implicit def toOptionPropertyValueDecoder[T](implicit decoder: PropertyValueDecoder[T]): PropertyValueDecoder[Option[T]] =
    new OptionalPropertyValueDecoder()

  final def from[T](creator: String => Either[String, T])
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
        case None => Right(None)
      }
    }
  }

  private final class RequiredPropertyValueDecoder[T](val creator: String => Either[String, T])
                                             (implicit val provider: PropertiesProvider)
    extends PropertyValueDecoder[T] {

    override def decode(propertyName: NonEmptyString): Either[String, T] = {
      for {
        // todo: better message
        propertyValue <- provider.getProperty(PropName(propertyName)).toRight(s"Cannot find property with name '$propertyName'")
        value <- creator(propertyValue)
      } yield value
    }
  }
}