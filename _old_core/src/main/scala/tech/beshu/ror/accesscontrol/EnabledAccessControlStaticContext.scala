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
package tech.beshu.ror.accesscontrol

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.rules.{FieldsRule, FilterRule}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.Header

trait AccessControlStaticContext {
  def involvesFilter: Boolean
  def doesRequirePassword: Boolean
  def forbiddenRequestMessage: String
  def obfuscatedHeaders: Set[Header.Name]
}

class EnabledAccessControlStaticContext(blocks: NonEmptyList[Block],
                                        showBasicAuthPrompt: Boolean,
                                        val forbiddenRequestMessage: String,
                                        override val obfuscatedHeaders: Set[Header.Name],
                                       )
  extends AccessControlStaticContext {

  val involvesFilter: Boolean = {
    blocks
      .find(_
        .rules
        .collect {
          case _: FilterRule => true
          case _: FieldsRule => true
        }
        .nonEmpty
      )
      .isDefined
  }

  val doesRequirePassword: Boolean = {
    showBasicAuthPrompt &&
      blocks
        .find(_
          .rules
          .collect {
            case _: AuthenticationRule => true
            case _: AuthorizationRule => true
          }
          .nonEmpty
        )
        .isDefined
  }
}

object DisabledAccessControlStaticContext$ extends AccessControlStaticContext {
  override val involvesFilter: Boolean = false
  override val doesRequirePassword: Boolean = false
  override val forbiddenRequestMessage: String = ""
  override val obfuscatedHeaders: Set[Header.Name] = Set.empty
}