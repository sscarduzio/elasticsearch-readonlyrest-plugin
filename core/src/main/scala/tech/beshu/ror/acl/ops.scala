package tech.beshu.ror.acl

import cats.implicits._
import cats.{Order, Show}
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import shapeless.Nat
import tech.beshu.ror.IPMask
import tech.beshu.ror.acl.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.blocks.definitions.{ProxyAuth, UserDef}
import tech.beshu.ror.acl.header.ToHeaderValue
import tech.beshu.ror.commons.utils.FilterTransient

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object header {

  class FlatHeader(val header: Header) extends AnyVal {
    def flatten: String = s"${header.name.value.toLowerCase()}:${header.value}"
  }
  object FlatHeader {
    implicit def toFlatHeader(header: Header): FlatHeader = new FlatHeader(header)
  }

  class ToTuple(val header: Header) extends AnyVal {
    def toTuple: (String, String) = (header.name.value, header.value)
  }
  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

  trait ToHeaderValue[T] {
    def toRawValue(t: T): String
  }
  object ToHeaderValue {
    def apply[T](func: T => String): ToHeaderValue[T] = new ToHeaderValue[T]() {
      override def toRawValue(t: T): String = func(t)
    }
  }
}

object unresolvedaddress {
  implicit val order: Order[Address] = Order.fromOrdering(Ordering.by { u: Address => u.value })
}

object orders {
  implicit val headerNameOrder: Order[Header.Name] = Order.by(_.value)
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value))
  implicit val unresolvedAddressOrder: Order[Address] = Order.by(_.value)
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val userIdOrder: Order[User.Id] = Order.by(_.value)
  implicit val ipMaskOrder: Order[IPMask] = Order.by(_.hashCode())
  implicit val apiKeyOrder: Order[ApiKey] = Order.by(_.value)
  implicit val kibanaAppOrder: Order[KibanaApp] = Order.by(_.value)
  implicit val documentFieldOrder: Order[DocumentField] = Order.by(_.value)
  implicit val aDocumentFieldOrder: Order[ADocumentField] = Order.by(_.value)
  implicit val negatedDocumentFieldOrder: Order[NegatedDocumentField] = Order.by(_.value)
  implicit val actionOrder: Order[Action] = Order.by(_.value)
  implicit val authKeyOrder: Order[AuthData] = Order.by(_.value)
  implicit val indexOrder: Order[IndexName] = Order.by(_.value)
  implicit val groupOrder: Order[Group] = Order.by(_.value)
  implicit val userDefOrder: Order[UserDef] = Order.by(_.username.value)
}

object show {

  object logs {
    implicit val userIdShow: Show[User.Id] = Show.show(_.value)
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show(_.value)
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val uriShow: Show[Uri] = Show.show(_.toJavaUri.toString())
    implicit val headerNameShow: Show[Header.Name] = Show.show(_.value)
    implicit val headerShow: Show[Header] = Show.show {
      case Header(name@Header.Name.authorization, _) => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.show}"
    }
    implicit val documentFieldShow: Show[DocumentField] = Show.show {
      case f: ADocumentField => f.value
      case f: NegatedDocumentField => s"~${f.value}"
    }
    implicit val proxyAuthNameShow: Show[ProxyAuth.Name] = Show.show(_.value)
    implicit val indexNameShow: Show[IndexName] = Show.show(_.value)
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
  implicit def setHeaderValue[T : ToHeaderValue]: ToHeaderValue[Set[T]] = ToHeaderValue {
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
  implicit val groupHeaderValue: ToHeaderValue[Group] = ToHeaderValue(_.value)
}