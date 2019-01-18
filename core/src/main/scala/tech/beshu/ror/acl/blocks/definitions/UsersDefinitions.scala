package tech.beshu.ror.acl.blocks.definitions

import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.definitions.UserDef.Name
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule

final case class UsersDefinitions(users: Set[UserDef]) extends AnyVal

final case class UserDef(username: Name, groups: NonEmptySet[Group], authenticationRule: AuthenticationRule)
object UserDef {
  final case class Name(value: String) extends AnyVal
}
