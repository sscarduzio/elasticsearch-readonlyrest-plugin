package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.{Const, Value}
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.rules.{BaseSpecializedIndicesRule, IndicesRule, RepositoriesRule, SnapshotsRule}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.IndicesDecodersHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.orders._

object IndicesRuleDecoders extends RuleDecoderWithoutAssociatedFields[IndicesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .map(indices => new IndicesRule(IndicesRule.Settings(indices)))
)

object SnapshotsRuleDecoder extends RuleDecoderWithoutAssociatedFields[SnapshotsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .emapE { indices =>
      if(indices.contains(Const(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(Const(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${SnapshotsRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new SnapshotsRule(BaseSpecializedIndicesRule.Settings(indices)))
)

object RepositoriesRuleDecoder extends RuleDecoderWithoutAssociatedFields[RepositoriesRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[IndexName]]
    .emapE { indices =>
      if(indices.contains(Const(IndexName.all)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.all.show}")))
      else if(indices.contains(Const(IndexName.wildcard)))
        Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.name.show}) that matches all the values is redundant - index ${IndexName.wildcard.show}")))
      else
        Right(indices)
    }
    .map(indices => new RepositoriesRule(BaseSpecializedIndicesRule.Settings(indices)))
)

private object IndicesDecodersHelper {
  implicit val indexNameValueDecoder: Decoder[Value[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .emapE { e =>
        Value.fromString(e, rv => Right(IndexName(rv.value)))
          .left.map(error => RulesLevelCreationError(Message(error.msg)))
      }
}