/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import io.circe.Decoder
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.JwtDefinitionsDecoder.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.JwtAuthRuleDecoder.cannotFindJwtDefinition
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

private implicit val ruleName: RuleName[Rule] = new RuleName[Rule] {
  override def name: Rule.Name = JwtAuthRule.Name.name
}

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef],
                         globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[JwtAuthRule | JwtAuthenticationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[JwtAuthRule | JwtAuthenticationRule]] = {
    JwtAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(JwtAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE[RuleDefinition[JwtAuthRule | JwtAuthenticationRule]] { case (name, groupsLogicOpt) =>
        val foundKbnDef = jwtDefinitions.items.find(_.id === name)
        (foundKbnDef, groupsLogicOpt) match {
          case (Some(jwtDef), Some(groupsLogic)) =>
            val authentication = new JwtAuthenticationRule(JwtAuthenticationRule.Settings(jwtDef), globalSettings.userIdCaseSensitivity)
            val authorization = new JwtAuthorizationRule(JwtAuthorizationRule.Settings(jwtDef, groupsLogic))
            val rule = new JwtAuthRule(authentication, authorization)
            Right(RuleDefinition.create(rule))
          case (Some(jwtDef), None) =>
            val rule = new JwtAuthenticationRule(JwtAuthenticationRule.Settings(jwtDef), globalSettings.userIdCaseSensitivity)
            Right(RuleDefinition.create(rule): RuleDefinition[JwtAuthenticationRule])
          case (None, _) =>
            Left(cannotFindJwtDefinition(name))
        }
      }
      .decoder
  }
}

private object JwtAuthRuleDecoder {

  def cannotFindJwtDefinition(name: JwtDef.Name) =
    RulesLevelCreationError(Message(s"Cannot find JWT definition with name: ${name.show}"))

  private val nameAndGroupsSimpleDecoder: Decoder[(JwtDef.Name, Option[GroupsLogic])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(JwtDef.Name.apply)
      .map((_, None))

  private val nameAndGroupsExtendedDecoder: Decoder[(JwtDef.Name, Option[GroupsLogic])] =
    Decoder
      .instance { c =>
        for {
          jwtDefName <- c.downField("name").as[JwtDef.Name]
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[JwtAuthRule].apply(c)
        } yield (jwtDefName, groupsLogicDecodingResult)
      }
      .toSyncDecoder
      .emapE {
        case (name, groupsLogicDecodingResult) =>
          groupsLogicDecodingResult match {
            case GroupsLogicDecodingResult.Success(groupsLogic) =>
              Right((name, Some(groupsLogic)))
            case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
              Right((name, None))
            case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, fields) =>
              val fieldsStr = fields.map(f => s"'$f'").mkString(" or ")
              Left(RulesLevelCreationError(Message(
                s"Please specify either $fieldsStr for JWT authorization rule '${name.show}'"
              )))
          }
      }
      .decoder

}