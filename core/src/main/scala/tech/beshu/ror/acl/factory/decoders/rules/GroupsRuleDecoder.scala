package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import cats.data.NonEmptySet
import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.definitions.{UserDef, UsersDefinitions}
import tech.beshu.ror.acl.blocks.rules.GroupsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.GroupsRuleDecoderHelper._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.utils.CirceOps._

import scala.collection.SortedSet

class GroupsRuleDecoder(usersDefinitions: UsersDefinitions) extends RuleDecoderWithoutAssociatedFields[GroupsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Group]]
    .emapE { groups =>
      NonEmptySet.fromSet(SortedSet.empty[UserDef] ++ usersDefinitions.users) match {
        case Some(userDefs) => Right(new GroupsRule(GroupsRule.Settings(groups, userDefs)))
        case None => Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${GroupsRule.name.show}` requires them.")))
      }
    }
)

private object GroupsRuleDecoderHelper {
  implicit val groupValueDecoder: Decoder[Value[Group]] =
    DecoderHelpers.alwaysRightValueDecoder[Group](rv => Group(rv.value))
}
