package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.GroupsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.GroupsRuleDecoderHelper._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.utils.CirceOps._

import scala.collection.SortedSet

class GroupsRuleDecoder(usersDefinitions: Definitions[UserDef]) extends RuleDecoderWithoutAssociatedFields[GroupsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Group]]
    .emapE { groups =>
      NonEmptySet.fromSet(SortedSet.empty[UserDef] ++ usersDefinitions.items) match {
        case Some(userDefs) => Right(new GroupsRule(GroupsRule.Settings(groups, userDefs)))
        case None => Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${GroupsRule.name.show}` requires them.")))
      }
    }
)

private object GroupsRuleDecoderHelper {
  implicit val groupValueDecoder: Decoder[Value[Group]] =
    DecoderHelpers
      .valueDecoder[Group] { rv =>
      NonEmptyString.from(rv.value) match {
        case Right(nonEmptyResolvedValue) => Right(Group(nonEmptyResolvedValue))
        case Left(_) => Left(ConvertError(rv, "Group cannot be empty"))
      }
    }
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(RulesLevelCreationError(Message(s"${error.msg}: ${error.resolvedValue.show}")))
      }
}
