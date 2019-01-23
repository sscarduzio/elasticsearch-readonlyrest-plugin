package tech.beshu.ror.acl.blocks.definitions

import cats.Show
import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.acl.show.logs.userIdShow

final case class UserDef(id: User.Id,
                         groups: NonEmptySet[Group],
                         authenticationRule: AuthenticationRule) extends Item {
  override type Id = User.Id
  override implicit val show: Show[User.Id] = userIdShow
}
