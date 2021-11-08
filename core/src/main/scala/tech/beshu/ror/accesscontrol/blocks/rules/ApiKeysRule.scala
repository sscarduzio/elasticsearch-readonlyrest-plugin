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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.ApiKeysRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.ApiKey
import tech.beshu.ror.accesscontrol.domain.Header.Name._

class ApiKeysRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = ApiKeysRule.Name.name

  def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    RuleResult.resultBasedOnCondition(blockContext) {
      blockContext
        .requestContext
        .headers
        .find(_.name === xApiKeyHeaderName)
        .exists { header => settings.apiKeys.contains(ApiKey(header.value)) }
    }
  }
}

object ApiKeysRule {

  implicit case object Name extends RuleName[ApiKeysRule] {
    override val name = Rule.Name("api_keys")
  }

  final case class Settings(apiKeys: NonEmptySet[ApiKey])
}