package tech.beshu.ror.acl.factory.decoders

import io.circe.Decoder
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.rules.IndicesRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.acl.utils.CirceOps._
import IndicesRuleDecodersHelper._

object IndicesRuleDecoders extends RuleDecoderWithoutAssociatedFields[IndicesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .map(indices => new IndicesRule(IndicesRule.Settings(indices)))
)

private object IndicesRuleDecodersHelper {
  implicit val indexNameValueDecoder: Decoder[Value[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .emapE { e =>
        Value.fromString(e, rv => Right(IndexName(rv.value)))
          .left.map(error => RulesLevelCreationError(Message(error.msg)))
      }
}