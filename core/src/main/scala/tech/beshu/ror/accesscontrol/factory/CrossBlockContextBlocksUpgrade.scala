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
import tech.beshu.ror.accesscontrol.blocks.rules.{LdapAuthRule, LdapAuthorizationRule, Rule}
import tech.beshu.ror.accesscontrol.logging.LoggingContext
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

object CrossBlockContextBlocksUpgrade {

  def upgrade(blocks: NonEmptyList[Block])
             (implicit loggingContext: LoggingContext): NonEmptyList[Block] = {
    val crossBlocksAvailableLdapGroups = getAllAvailableLdapGroups(blocks)
    val upgradedBlocks = blocks.foldLeft(Vector.empty[Block]) {
      case (modifiedBlocks, currentBlock) =>
        val upgradedRules = currentBlock.rules.foldLeft(Vector.empty[Rule]) {
          case (modifiedRules, currentRule: LdapAuthorizationRule) =>
            modifiedRules :+ new LdapAuthorizationRule(currentRule.settings.copy(allLdapGroups = UniqueNonEmptyList.unsafeFromSortedSet(crossBlocksAvailableLdapGroups)))
          case (modifiedRules, currentRule: LdapAuthRule) =>
            modifiedRules :+ new LdapAuthRule(
              currentRule.authentication,
              new LdapAuthorizationRule(currentRule.authorization.settings.copy(allLdapGroups = UniqueNonEmptyList.unsafeFromSortedSet(crossBlocksAvailableLdapGroups)))
            )
          case (modifiedRules, currentRule) =>
            modifiedRules :+ currentRule
        }
        modifiedBlocks :+
          new Block(
            currentBlock.name,
            currentBlock.policy,
            currentBlock.verbosity,
            NonEmptyList.fromListUnsafe(upgradedRules.toList),
            currentBlock.globalSettings
          )
    }
    NonEmptyList.fromListUnsafe(upgradedBlocks.toList)
  }

  private def getAllAvailableLdapGroups(blocks: NonEmptyList[Block]) = {
    UniqueList.fromList(
      blocks.toList
        .flatMap(_
          .rules
          .collect {
            case r: LdapAuthorizationRule => r
            case r: LdapAuthRule => r.authorization
          }
          .flatMap(_.settings.permittedGroups.toNonEmptyList.toList)
        )
    )
  }
}
