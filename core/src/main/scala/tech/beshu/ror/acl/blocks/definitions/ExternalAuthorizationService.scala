package tech.beshu.ror.acl.blocks.definitions

import cats.{Eq, Show}
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.{Group, LoggedUser}
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService.Name
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

trait ExternalAuthorizationService extends Item {
  override type Id = Name
  def id: Name
  def grantsFor(loggedUser: LoggedUser): Task[Set[Group]]

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthorizationService {

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value)
  }
}

class HttpExternalAuthorizationService extends ExternalAuthorizationService {
  override def id: Name = ???
  override def grantsFor(loggedUser: LoggedUser): Task[Set[Group]] = ???
}

class CachingExternalAuthorizationService extends ExternalAuthorizationService {
  override def id: Name = ???
  override def grantsFor(loggedUser: LoggedUser): Task[Set[Group]] = ???
}