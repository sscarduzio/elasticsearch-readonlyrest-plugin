package tech.beshu.ror.commons.domain

import cats.Eq

final case class LoggedUser(id: User.Id)
object LoggedUser {
  implicit val eqLoggedUser: Eq[LoggedUser] = Eq.fromUniversalEquals
}

object User {
  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val eqId: Eq[Id] = Eq.fromUniversalEquals
  }
}