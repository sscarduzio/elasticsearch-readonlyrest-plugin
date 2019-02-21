package tech.beshu.ror.acl.blocks.definitions

import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.LdapAuthService.{Credentials, LdapUser}
import tech.beshu.ror.acl.domain.{Group, Secret, User}

trait LdapAuthService {

  def ldapUser(id: User.Id): Task[Option[LdapUser]]
  def authenticate(credentials: Credentials): Task[Boolean]
  def groupsOf(user: LdapUser): Task[Set[Group]]
}

object LdapAuthService {
  final case class Credentials(userName: User.Id, secret: Secret)
  final case class LdapUser(uid: String, dn: String)
}

class UnboundidLdapAuthService extends LdapAuthService {
  override def ldapUser(id: User.Id): Task[Option[LdapUser]] = ???

  override def groupsOf(user: LdapUser): Task[Set[Group]] = ???

  override def authenticate(credentials: Credentials): Task[Boolean] = ???
}