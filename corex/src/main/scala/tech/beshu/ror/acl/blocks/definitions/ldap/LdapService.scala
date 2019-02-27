package tech.beshu.ror.acl.blocks.definitions.ldap

import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

sealed trait LdapService extends Item {
  override type Id = Name
  def id: Id

  override implicit def show: Show[Name] = Name.nameShow
}

object LdapService {
  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

trait LdapUserService extends LdapService {
  def ldapUserBy(userId: User.Id): Task[Option[LdapUser]]
}

trait LdapAuthenticationService extends LdapUserService {
  def authenticate(user: User.Id, secret: Secret): Task[Boolean]
}

trait LdapAuthorizationService extends LdapUserService {
  def groupsOf(id: User.Id): Task[Set[Group]]
}

trait LdapAuthService extends LdapAuthenticationService with LdapAuthorizationService

class ComposedLdapAuthService(override val id: LdapService#Id,
                              ldapAuthenticationService: LdapAuthenticationService,
                              ldapAuthorizationService: LdapAuthorizationService)
  extends LdapAuthService {

  def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    ldapAuthenticationService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: Secret): Task[Boolean] =
    ldapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id): Task[Set[Group]] =
    ldapAuthorizationService.groupsOf(id)
}


final case class LdapUser(id: User.Id, dn: Dn)
final case class Dn(value: NonEmptyString)

