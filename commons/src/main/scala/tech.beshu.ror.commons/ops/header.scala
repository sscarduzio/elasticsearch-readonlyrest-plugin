package tech.beshu.ror.commons.ops


import cats.Order
import tech.beshu.ror.commons.aDomain.Header

import scala.language.implicitConversions

object header {

  class FlatHeader(header: Header) {
    def flatten: String = s"${header.name}:${header.value}"
  }

  object FlatHeader {
    implicit def toFlatHeader(header: Header): FlatHeader = new FlatHeader(header)
  }

  class ToTuple(header: Header) {
    def toTuple: (String, String) = (header.name, header.value)
  }

  object ToTuple {
    implicit def toTuple(header: Header): ToTuple = new ToTuple(header)
  }

  implicit val order: Order[Header] = Order.fromOrdering(Ordering.by { h: Header => (h.name, h.value) })
}
