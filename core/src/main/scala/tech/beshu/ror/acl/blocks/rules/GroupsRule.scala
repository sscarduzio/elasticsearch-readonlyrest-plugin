package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.definitions.UsersDefinitions
import tech.beshu.ror.acl.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext

class GroupsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = GroupsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = ???
}

object GroupsRule {
  val name = Rule.Name("groups")

  final case class Settings(groups: NonEmptySet[Value[Group]],
                            usersDefinitions: UsersDefinitions)
}
