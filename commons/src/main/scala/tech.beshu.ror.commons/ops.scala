package tech.beshu.ror.commons

import cats.{Order, Show}
import cats.implicits._
import com.softwaremill.sttp.{Method, Uri}
import eu.timepit.refined.api.Validate
import eu.timepit.refined.numeric.Greater
import shapeless.Nat
import tech.beshu.ror.commons.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain._
import tech.beshu.ror.commons.domain._

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
  implicit val aDocumentFieldOrder: Order[ADocumentField] = Order.by(_.value)
  implicit val negatedDocumentFieldOrder: Order[NegatedDocumentField] = Order.by(_.value)
  implicit def valueOrder[T: Order]: Order[Value[T]] = Order.from {
    case (a: Const[T], b: Const[T]) => implicitly[Order[T]].compare(a.value, b.value)
    case (_: Const[T], _: Variable[T]) => -1
    case (_: Variable[T], _: Const[T]) => 1
    case (a: Variable[T], b: Variable[T]) => a.representation.compareTo(b.representation)
  }
}

object show {

  object logs {
    implicit val userIdShow: Show[User.Id] = Show.show(_.value)
    implicit val loggedUserShow: Show[LoggedUser] = Show.show(_.id.value)
    implicit val typeShow: Show[Type] = Show.show(_.value)
    implicit val actionShow: Show[Action] = Show.show(_.value)
    implicit val addressShow: Show[Address] = Show.show(_.value)
    implicit val methodShow: Show[Method] = Show.show(_.m)
    implicit val uriShow: Show[Uri] = Show.show(_.toString())
    implicit val headerNameShow: Show[Header.Name] = Show.show(_.value)
    implicit val headerShow: Show[Header] = Show.show {
      case Header(name@Name.authorization, _) => s"${name.show}=<OMITTED>"
      case Header(name, value) => s"${name.show}=${value.show}"
    }
  }

}

object refined {
  implicit val finiteDurationValidate: Validate[FiniteDuration, Greater[Nat._0]] = Validate.fromPredicate(
    (d: FiniteDuration) => d.length > 0,
    (d: FiniteDuration) => s"$d is positive",
    Greater(shapeless.nat._0)
  )
}