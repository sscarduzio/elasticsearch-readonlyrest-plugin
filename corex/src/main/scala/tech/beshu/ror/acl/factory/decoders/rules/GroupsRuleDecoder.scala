package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.GroupsRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps._

import scala.collection.SortedSet

class GroupsRuleDecoder(usersDefinitions: Definitions[UserDef]) extends RuleDecoderWithoutAssociatedFields[GroupsRule](
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Group]]
    .mapError(RulesLevelCreationError.apply)
    .emapE { groups =>
      NonEmptySet.fromSet(SortedSet.empty[UserDef] ++ usersDefinitions.items) match {
        case Some(userDefs) => Right(new GroupsRule(GroupsRule.Settings(groups, userDefs)))
        case None => Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${GroupsRule.name.show}` requires them.")))
      }
    }
)
