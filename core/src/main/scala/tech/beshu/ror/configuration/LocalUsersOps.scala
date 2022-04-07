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
package tech.beshu.ror.configuration

import cats.implicits._
import cats.kernel.Monoid
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.rules.UsersRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.UserIdPatterns
import tech.beshu.ror.configuration.RorConfig.LocalUsers

private[configuration] object LocalUsersOps {

  private val noUsers: LocalUsers = LocalUsers(users = Set.empty, unknownUsers = false)

  private implicit val monoid: Monoid[LocalUsers] = Monoid.instance(
    emptyValue = LocalUsers(Set.empty, unknownUsers = false),
    cmb = (e1, e2) => {
      val withUnknownUsers = e1.unknownUsers || e2.unknownUsers
      LocalUsers(e1.users ++ e2.users, withUnknownUsers)
    }
  )

  def fromRorConfig(rorConfig: RorConfig): LocalUsers = {
    List(
      fromConfigBlocks(rorConfig.blocks),
      fromConfigUsers(rorConfig.users)
    )
      .combineAll
  }

  private def fromConfigUsers(users: Seq[UserIdPatterns]): LocalUsers = {
    LocalUsers(users = Set.empty, unknownUsers = users.nonEmpty)
  }

  private def fromConfigBlocks(blocks: Seq[Block]): LocalUsers = {
    blocks
      .map(fromBlock)
      .toList
      .combineAll
  }

  private def fromBlock(block: Block): LocalUsers = {
    block
      .rules
      .map {
        case rule: Rule.RegularRule => fromRule(rule)
        case rule: Rule.AuthRule => fromRule(rule)
        case _: Rule.AuthorizationRule => noUsers
        case rule: Rule.AuthenticationRule => fromRule(rule)
      }
      .combineAll
  }

  private def fromRule(rule: Rule.RegularRule): LocalUsers = rule match {
    case rule: UsersRule => fromUsersRule(rule).combineAll
    case _ => noUsers
  }

  private def fromUsersRule(rule: UsersRule): List[LocalUsers] = {
    rule.settings.userIds
      .toList
      .map {
        case RuntimeMultiResolvableVariable.AlreadyResolved(users) =>
          RorConfig.LocalUsers(users.toList.toSet, unknownUsers = false)
        case RuntimeMultiResolvableVariable.ToBeResolved(_) =>
          RorConfig.LocalUsers(Set.empty, unknownUsers = true)
      }
  }

  private def fromRule(rule: Rule.AuthenticationRule): LocalUsers = rule.eligibleUsers match {
    case EligibleUsersSupport.Available(users) =>
      RorConfig.LocalUsers(users, unknownUsers = false)
    case EligibleUsersSupport.NotAvailable =>
      RorConfig.LocalUsers(Set.empty, unknownUsers = true)
  }
}
