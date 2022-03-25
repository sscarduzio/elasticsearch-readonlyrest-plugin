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
package tech.beshu.ror.accesscontrol.factory

import cats.data._
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.{ExternalAuthorizationRule, LdapAuthRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.logging.LoggingContext
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

object CrossBlockContextBlocksUpgrade {

  def upgrade(blocks: NonEmptyList[Block])
             (implicit loggingContext: LoggingContext): NonEmptyList[Block] = {
    val upgradePipeline = (updateLdapAuthRulesSettings(_)) andThen (updateExternalAuthorizationRulesSettings(_))
    upgradePipeline apply blocks
  }

  private def updateLdapAuthRulesSettings(blocks: NonEmptyList[Block])
                                         (implicit loggingContext: LoggingContext) = {
    val crossBlocksAvailableLdapGroups = getAllAvailableLdapGroups(blocks)
    val upgradedBlocks = blocks.foldLeft(Vector.empty[Block]) {
      case (modifiedBlocks, currentBlock) =>
        val upgradedRules = currentBlock.rules.foldLeft(Vector.empty[Rule]) {
          case (modifiedRules, currentRule: LdapAuthorizationRule) =>
            val newSettings = currentRule.settings.copy(
              allLdapGroups = crossBlocksAvailableLdapGroups(currentRule.settings.ldap.id)
            )
            modifiedRules :+ new LdapAuthorizationRule(newSettings, currentRule.impersonation, currentRule.caseMappingEquality)
          case (modifiedRules, currentRule: LdapAuthRule) =>
            val newSettings = currentRule.authorization.settings.copy(
              allLdapGroups = crossBlocksAvailableLdapGroups(currentRule.authorization.settings.ldap.id)
            )
            modifiedRules :+ new LdapAuthRule(
              currentRule.authentication,
              new LdapAuthorizationRule(newSettings, currentRule.authorization.impersonation, currentRule.authorization.caseMappingEquality)
            )
          case (modifiedRules, currentRule) =>
            modifiedRules :+ currentRule
        }
        modifiedBlocks :+
          new Block(
            currentBlock.name,
            currentBlock.policy,
            currentBlock.verbosity,
            NonEmptyList.fromListUnsafe(upgradedRules.toList)
          )
    }
    NonEmptyList.fromListUnsafe(upgradedBlocks.toList)
  }

  private def getAllAvailableLdapGroups(blocks: NonEmptyList[Block]) = {
    blocks.toList
      .flatMap(_
        .rules
        .collect {
          case r: LdapAuthorizationRule => r
          case r: LdapAuthRule => r.authorization
        }
      )
      .foldLeft(Map.empty[LdapService#Id, UniqueNonEmptyList[Group]]) {
        case (acc, rule) =>
          val newGroups = acc.get(rule.settings.ldap.id) match {
            case Some(groups) => UniqueNonEmptyList.unsafeFromList(groups.toList ::: rule.settings.permittedGroups.getPermittedGroups().toList)
            case None => rule.settings.permittedGroups.getPermittedGroups()
          }
          acc + (rule.settings.ldap.id -> newGroups)
      }
  }

  private def updateExternalAuthorizationRulesSettings(blocks: NonEmptyList[Block])
                                                      (implicit loggingContext: LoggingContext) = {
    val crossBlocksAvailableExternalServiceGroups = getAllAvailableExternalServiceGroups(blocks)
    val upgradedBlocks = blocks.foldLeft(Vector.empty[Block]) {
      case (modifiedBlocks, currentBlock) =>
        val upgradedRules = currentBlock.rules.foldLeft(Vector.empty[Rule]) {
          case (modifiedRules, currentRule: ExternalAuthorizationRule) =>
            val newSettings = currentRule.settings.copy(
              allExternalServiceGroups = crossBlocksAvailableExternalServiceGroups(currentRule.settings.service.id)
            )
            modifiedRules :+ new ExternalAuthorizationRule(newSettings, currentRule.impersonation, currentRule.caseMappingEquality)
          case (modifiedRules, currentRule) =>
            modifiedRules :+ currentRule
        }
        modifiedBlocks :+
          new Block(
            currentBlock.name,
            currentBlock.policy,
            currentBlock.verbosity,
            NonEmptyList.fromListUnsafe(upgradedRules.toList)
          )
    }
    NonEmptyList.fromListUnsafe(upgradedBlocks.toList)
  }

  private def getAllAvailableExternalServiceGroups(blocks: NonEmptyList[Block]) = {
    blocks.toList
      .flatMap(_
        .rules
        .collect {
          case r: ExternalAuthorizationRule => r
        }
      )
      .foldLeft(Map.empty[ExternalAuthorizationService#Id, UniqueNonEmptyList[Group]]) {
        case (acc, rule) =>
          val newGroups = acc.get(rule.settings.service.id) match {
            case Some(groups) => UniqueNonEmptyList.unsafeFromList(groups.toList ::: rule.settings.permittedGroups.toList)
            case None => rule.settings.permittedGroups
          }
          acc + (rule.settings.service.id -> newGroups)
      }
  }
}
