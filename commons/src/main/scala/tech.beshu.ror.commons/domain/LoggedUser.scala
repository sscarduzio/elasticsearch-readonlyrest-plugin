package tech.beshu.ror.commons.domain

import tech.beshu.ror.commons.domain.LoggedUser.Id

final case class LoggedUser(id: Id)
object LoggedUser {
  final case class Id(value: String) extends AnyVal
}
