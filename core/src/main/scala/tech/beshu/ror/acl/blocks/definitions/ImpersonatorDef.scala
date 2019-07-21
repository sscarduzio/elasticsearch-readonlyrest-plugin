package tech.beshu.ror.acl.blocks.definitions

import cats.Show
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.show.logs.userIdShow
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

final case class ImpersonatorDef(id: User.Id,
                                 authenticationRule: AuthenticationRule,
                                 users: Set[User.Id])
  extends Item {

  override type Id = User.Id
  override implicit def show: Show[User.Id] = userIdShow
}
