package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.rules.JwtAuthRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.definitions.JwtDefinitionsDecoder._
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.factory.decoders.common._

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef]) extends RuleDecoderWithoutAssociatedFields[JwtAuthRule](
  JwtAuthRuleDecoder.nameAndGroupsSimpleDecoder
    .or(JwtAuthRuleDecoder.nameAndGroupsExtendedDecoder)
    .emapE { case (name, groups) =>
      jwtDefinitions.items.find(_.id === name) match {
        case Some(jwtDef) => Right((jwtDef, groups))
        case None => Left(RulesLevelCreationError(Message(s"Cannot find JWT definition with name: ${name.show}")))
      }
    }
    .map { case (jwtDef, groups) =>
      new JwtAuthRule(JwtAuthRule.Settings(jwtDef, groups))
    }
)

private object JwtAuthRuleDecoder {

  private implicit val groupsSetDecoder: Decoder[Set[Value[Group]]] = DecoderHelpers.decodeStringLikeOrSet[Value[Group]]

  private val nameAndGroupsSimpleDecoder: Decoder[(JwtDef.Name, Set[Group])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(JwtDef.Name.apply)
      .map((_, Set.empty))

  private val nameAndGroupsExtendedDecoder: Decoder[(JwtDef.Name, Set[Group])] =
    Decoder.instance { c =>
      for {
        jwtDefName <- c.downField("name").as[JwtDef.Name]
        groups <- c.downField("roles").as[Option[Set[Group]]]
      } yield (jwtDefName, groups.getOrElse(Set.empty))
    }

}