package tech.beshu.ror.acl.factory.decoders.rules

import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.definitions.UsersDefinitions
import tech.beshu.ror.acl.blocks.rules.GroupsRule
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.GroupsRuleDecoderHelper._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers

class GroupsRuleDecoder(usersDefinitions: UsersDefinitions) extends RuleDecoderWithoutAssociatedFields[GroupsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Group]]
    .map(groups => new GroupsRule(GroupsRule.Settings(groups, usersDefinitions)))
)

private object GroupsRuleDecoderHelper {
  implicit val groupValueDecoder: Decoder[Value[Group]] =
    DecoderHelpers.alwaysRightValueDecoder[Group](rv => Group(rv.value))
}
