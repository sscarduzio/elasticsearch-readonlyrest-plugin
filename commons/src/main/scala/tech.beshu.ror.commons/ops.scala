package tech.beshu.ror.commons

import cats.Order
import cats.implicits._
import com.softwaremill.sttp.Method
import tech.beshu.ror.commons.aDomain.{Address, Header}
import tech.beshu.ror.commons.domain._

import scala.language.implicitConversions

object header {

  class FlatHeader(val header: Header) extends AnyVal {
    def flatten: String = s"${header.name.toLowerCase()}:${header.value}"
  }

  object FlatHeader {
    implicit def toFlatHeader(header: Header): FlatHeader = new FlatHeader(header)
  }

  class ToTuple(val header: Header) extends AnyVal {
    def toTuple: (String, String) = (header.name, header.value)
  }

  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

}

object unresolvedaddress {
  implicit val order: Order[Address] = Order.fromOrdering(Ordering.by { u: Address => u.value })
}

object orders {
  implicit val headerOrder: Order[Header] = Order.by(h => (h.name, h.value))
  implicit val unresolvedAddressOrder: Order[Address] = Order.by(_.value)
  implicit val methodOrder: Order[Method] = Order.by(_.m)
  implicit val loggedUserIdOrder: Order[LoggedUser.Id] = Order.by(_.value)
  implicit val ipMaskOrder: Order[IPMask] = Order.by(_.hashCode())
  implicit def valueOrder[T: Order]: Order[Value[T]] = Order.from {
    case (a: Const[T], b: Const[T]) => implicitly[Order[T]].compare(a.value, b.value)
    case (_: Const[T], _: Variable[T]) => -1
    case (_: Variable[T], _: Const[T]) => 1
    case (a: Variable[T], b: Variable[T]) => a.representation.compareTo(b.representation)
  }
}
