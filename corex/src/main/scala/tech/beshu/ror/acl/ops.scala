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
package tech.beshu.ror.acl

import cats.implicits._
import cats.{Order, Show}
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import eu.timepit.refined.types.string.NonEmptyString
import shapeless.Nat
import tech.beshu.ror.acl.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.blocks.RuleOrdering
import tech.beshu.ror.acl.blocks.definitions.ldap.Dn
import tech.beshu.ror.acl.blocks.definitions.{ExternalAuthenticationService, ProxyAuth, UserDef}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.header.ToHeaderValue
import tech.beshu.ror.utils.FilterTransient

import scala.collection.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object header {

  class FlatHeader(val header: Header) extends AnyVal {
    def flatten: String = s"${header.name.value.value.toLowerCase()}:${header.value}"
  }
  object FlatHeader {
    implicit def from(header: Header): FlatHeader = new FlatHeader(header)
  }

  class ToTuple(val header: Header) extends AnyVal {
    def toTuple: (String, String) = (header.name.value.value, header.value.value)
  }
  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

  trait ToHeaderValue[T] {
    def toRawValue(t: T): String // todo: improvement for the future (NonEmptyString)
  }
  object ToHeaderValue {
    def apply[T](func: T => String): ToHeaderValue[T] = new ToHeaderValue[T]() {
      override def toRawValue(t: T): String = func(t)
    }
  }
}

object orders {
  implicit val nonEmptyStringOrder: Order[NonEmptyString] = Order.by(_.value)
  implicit val headerNameOrder: Order[Header.Name] = Order.by(_.value.value)
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value.value))
  implicit val addressOrder: Order[Address] = Order.by {
    case Address.Ip(value) => value.toString()
    case Address.Name(value) => value.toString
  }
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val userIdOrder: Order[User.Id] = Order.by(_.value)
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by(_.value)
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val aDocumentFieldOrder: Order[ADocumentField] = Order.by(_.value)
  implicit val negatedDocumentFieldOrder: Order[NegatedDocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[Secret] = Order.by(_.value)
  implicit val indexOrder: Order[IndexName] = Order.by(_.value)
  implicit val groupOrder: Order[Group] = Order.by(_.value.value)
  implicit val userDefOrder: Order[UserDef] = Order.by(_.id.value)
  implicit val ruleNameOrder: Order[Rule.Name] = Order.by(_.value)
  implicit val ruleOrder: Order[Rule] = Order.fromOrdering(new RuleOrdering)
}

object show {

  object logs {
    implicit val nonEmptyStringShow: Show[NonEmptyString] = Show.show(_.value)
    implicit val userIdShow: Show[User.Id] = Show.show(_.value)
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show {
      case Address.Ip(value) => value.toString
      case Address.Name(value) => value.toString
    }
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val uriShow: Show[Uri] = Show.show(_.toJavaUri.toString())
    implicit val headerNameShow: Show[Header.Name] = Show.show(_.value.value)
    implicit val headerShow: Show[Header] = Show.show {
      case Header(name@Header.Name.authorization, _) => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.value.show}"
    }
    implicit val documentFieldShow: Show[DocumentField] = Show.show {
      case f: ADocumentField => f.value
      case f: NegatedDocumentField => s"~${f.value}"
    }
    implicit val proxyAuthNameShow: Show[ProxyAuth.Name] = Show.show(_.value)
    implicit val indexNameShow: Show[IndexName] = Show.show(_.value)
    implicit val externalAuthenticationServiceNameShow: Show[ExternalAuthenticationService.Name] = Show.show(_.value)
    implicit val groupShow: Show[Group] = Show.show(_.value.value)
    implicit val tokenShow: Show[AuthorizationToken] = Show.show(_.value.value)
    implicit val jwtTokenShow: Show[JwtToken] = Show.show(_.value.value)
    implicit val uriPathShow: Show[UriPath] = Show.show(_.value)
    implicit val dnShow: Show[Dn] = Show.show(_.value.value)
  }
}

object refined {
  implicit val finiteDurationValidate: Validate[FiniteDuration, Greater[Nat._0]] = Validate.fromPredicate(
    (d: FiniteDuration) => d.length > 0,
    (d: FiniteDuration) => s"$d is positive",
    Greater(shapeless.nat._0)
  )
}

object headerValues {
  implicit def setHeaderValue[T : ToHeaderValue]: ToHeaderValue[SortedSet[T]] = ToHeaderValue {
    val tToHeaderValue = implicitly[ToHeaderValue[T]]
    _.map(tToHeaderValue.toRawValue).mkString(",")
  }
  implicit val userIdHeaderValue: ToHeaderValue[User.Id] = ToHeaderValue(_.value)
  implicit val indexNameHeaderValue: ToHeaderValue[IndexName] = ToHeaderValue(_.value)
  implicit val transientFilterHeaderValue: ToHeaderValue[Filter] = ToHeaderValue { filter =>
    FilterTransient.createFromFilter(filter.value).serialize()
  }
  implicit val kibanaAccessHeaderValue: ToHeaderValue[KibanaAccess] = ToHeaderValue {
    case KibanaAccess.RO => "RO"
    case KibanaAccess.ROStrict => "RO_STRICT"
    case KibanaAccess.RW => "RW"
    case KibanaAccess.Admin => "ADMIN"
  }
  implicit val groupHeaderValue: ToHeaderValue[Group] = ToHeaderValue(_.value.value)
}