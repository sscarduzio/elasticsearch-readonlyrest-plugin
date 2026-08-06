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

import cats.implicits.*
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, PkiDef}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{PkiAuthRule, PkiAuthenticationRule, PkiAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.{GroupsLogic, User}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.PkiDefinitionsDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.PkiRulesDecodersHelper.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

// ------ pki_authentication
class PkiAuthenticationRuleDecoder(
    pkiDefinitions: Definitions[PkiDef],
    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
    mocksProvider: MocksProvider,
    globalSettings: GlobalSettings
) extends RuleBaseDecoderWithoutAssociatedFields[PkiAuthenticationRule] {

  override protected def decoder: Decoder[RuleDefinition[PkiAuthenticationRule]] = {
    simpleNameAndUsers
      .orElse(complexNameAndUsers)
      .toSyncDecoder
      .emapE { case (name, users) =>
        findPkiDef[PkiAuthenticationRule](name, pkiDefinitions, groupsRequired = false)
          .map(PkiAuthenticationRule.Settings(_, users))
      }
      .map(settings =>
        RuleDefinition.create(
          new PkiAuthenticationRule(
            settings,
            globalSettings.userIdCaseSensitivity,
            impersonatorsDef.toImpersonation(mocksProvider)
          )
        )
      )
      .decoder
  }

  private def simpleNameAndUsers: Decoder[(PkiDef.Name, Option[UniqueNonEmptyList[User.Id]])] =
    nameDecoder.map((_, None))

  private def complexNameAndUsers: Decoder[(PkiDef.Name, Option[UniqueNonEmptyList[User.Id]])] = {
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[PkiDef.Name]
          users <- c.downField("users").as[Option[UniqueNonEmptyList[User.Id]]]
        } yield (name, users)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }

}

// ------ pki_authorization
class PkiAuthorizationRuleDecoder(
    pkiDefinitions: Definitions[PkiDef],
    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
    mocksProvider: MocksProvider,
    globalSettings: GlobalSettings
) extends RuleBaseDecoderWithoutAssociatedFields[PkiAuthorizationRule] {

  override protected def decoder: Decoder[RuleDefinition[PkiAuthorizationRule]] = {
    nameAndGroupsLogic[PkiAuthorizationRule].toSyncDecoder
      .emapE { case (name, groupsLogic) =>
        findPkiDef[PkiAuthorizationRule](name, pkiDefinitions, groupsRequired = true)
          .map(PkiAuthorizationRule.Settings(_, groupsLogic))
      }
      .map(settings =>
        RuleDefinition.create(
          new PkiAuthorizationRule(
            settings,
            globalSettings.userIdCaseSensitivity,
            impersonatorsDef.toImpersonation(mocksProvider)
          )
        )
      )
      .decoder
  }

}

// ------ pki_auth
class PkiAuthRuleDecoder(
    pkiDefinitions: Definitions[PkiDef],
    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
    mocksProvider: MocksProvider,
    globalSettings: GlobalSettings
) extends RuleBaseDecoderWithoutAssociatedFields[PkiAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[PkiAuthRule]] = {
    nameAndGroupsLogic[PkiAuthRule].toSyncDecoder
      .emapE { case (name, groupsLogic) =>
        findPkiDef[PkiAuthRule](name, pkiDefinitions, groupsRequired = true).map { pki =>
          new PkiAuthRule(
            new PkiAuthenticationRule(
              PkiAuthenticationRule.Settings(pki, userIds = None),
              globalSettings.userIdCaseSensitivity,
              impersonatorsDef.toImpersonation(mocksProvider)
            ),
            new PkiAuthorizationRule(
              PkiAuthorizationRule.Settings(pki, groupsLogic),
              globalSettings.userIdCaseSensitivity,
              impersonatorsDef.toImpersonation(mocksProvider)
            )
          )
        }
      }
      .map(RuleDefinition.create(_))
      .decoder
  }

}

private object PkiRulesDecodersHelper {

  private[rules] def nameAndGroupsLogic[R <: Rule: RuleName]: Decoder[(PkiDef.Name, GroupsLogic)] = {
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[PkiDef.Name]
          groupsLogic <- GroupsLogicDecoder.simpleDecoder[R].apply(c)
        } yield (name, groupsLogic)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }

  private[rules] def findPkiDef[R <: Rule: RuleName](
      searchedPkiName: PkiDef.Name,
      pkiDefinitions: Definitions[PkiDef],
      groupsRequired: Boolean
  ): Either[CoreCreationError, PkiDef] = {
    pkiDefinitions.items.find(_.id === searchedPkiName) match {
      case Some(pki) if groupsRequired && pki.groupsExtraction.isEmpty =>
        Left(
          RulesLevelCreationError(
            Message(
              s"PKI: ${searchedPkiName.value.show} cannot be used in '${RuleName[R].show}' rule, because it doesn't define a 'groups' section"
            )
          )
        )
      case Some(pki) =>
        Right(pki)
      case None =>
        Left(RulesLevelCreationError(Message(s"Cannot find PKI with name: ${searchedPkiName.value.show}")))
    }
  }

}
