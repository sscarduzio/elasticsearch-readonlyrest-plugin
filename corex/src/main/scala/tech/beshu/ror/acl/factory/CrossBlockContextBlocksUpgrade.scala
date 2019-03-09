package tech.beshu.ror.acl.factory

import cats.implicits._
import cats.data._
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.rules.{LdapAuthRule, LdapAuthorizationRule, Rule}
import tech.beshu.ror.acl.domain.Group
import tech.beshu.ror.acl.orders._

import scala.collection.SortedSet

object CrossBlockContextBlocksUpgrade {

  def upgrade(blocks: NonEmptyList[Block]): NonEmptyList[Block] = {
    val crossBlocksAvailableLdapGroups = getAllAvailableLdapGroups(blocks)
    val upgradedBlocks = blocks.foldLeft(Vector.empty[Block]) {
      case (modifiedBlocks, currentBlock) =>
        val upgradedRules = currentBlock.rules.foldLeft(Vector.empty[Rule]) {
          case (modifiedRules, currentRule: LdapAuthorizationRule) =>
            modifiedRules :+ new LdapAuthorizationRule(currentRule.settings.copy(allLdapGroups = NonEmptySet.fromSetUnsafe(crossBlocksAvailableLdapGroups)))
          case (modifiedRules, currentRule: LdapAuthRule) =>
            modifiedRules :+ new LdapAuthRule(
              currentRule.authentication,
              new LdapAuthorizationRule(currentRule.authorization.settings.copy(allLdapGroups = NonEmptySet.fromSetUnsafe(crossBlocksAvailableLdapGroups)))
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
    SortedSet.empty[Group] ++
      blocks.toList
        .flatMap(_
          .rules
          .collect {
            case r: LdapAuthorizationRule => r
            case r: LdapAuthRule => r.authorization
          }
          .flatMap(_.settings.permittedGroups.toNonEmptyList.toList)
        )
        .toSet
  }
}
