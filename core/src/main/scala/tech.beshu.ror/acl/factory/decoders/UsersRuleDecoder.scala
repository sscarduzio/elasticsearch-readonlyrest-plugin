package tech.beshu.ror.acl.factory.decoders

import io.circe.Decoder
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderOps
import tech.beshu.ror.commons.domain.User
import tech.beshu.ror.commons.orders._
import UsersRuleDecoderHelper.userIdValueDecoder
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.acl.blocks.rules.UsersRule.Settings

object UsersRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderOps
    .decodeStringLikeOrNonEmptySet[Value[User.Id]]
    .map(users => new UsersRule(Settings(users)))
)

private object UsersRuleDecoderHelper {
  implicit val userIdValueDecoder: Decoder[Value[User.Id]] = DecoderOps.valueDecoder(rv => User.Id(rv.value))
}
