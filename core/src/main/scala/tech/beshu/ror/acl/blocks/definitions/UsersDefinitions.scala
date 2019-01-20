package tech.beshu.ror.acl.blocks.definitions

import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule

final case class UsersDefinitions(users: Set[UserDef]) extends AnyVal

final case class UserDef(username: User.Id,
                         groups: NonEmptySet[Group],
                         authenticationRule: AuthenticationRule)
