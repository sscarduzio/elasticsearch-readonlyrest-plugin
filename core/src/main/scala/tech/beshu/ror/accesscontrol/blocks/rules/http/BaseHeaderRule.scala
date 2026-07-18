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
package tech.beshu.ror.accesscontrol.blocks.rules.http

import cats.data.{NonEmptyList, NonEmptySet}
import cats.implicits.*
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.BaseHeaderRule.Settings
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

abstract class BaseHeaderRule(val settings: Settings) extends RegularRule

object BaseHeaderRule {

  final class Settings private (val compiledRequirements: NonEmptyList[CompiledHeaderRequirementMatcher]) {
    def headerAccessRequirements: NonEmptyList[AccessRequirement[Header]] =
      compiledRequirements.map(_.accessRequirement)
  }

  object Settings {
    def apply(headerAccessRequirements: NonEmptySet[AccessRequirement[Header]]): Settings =
      new Settings(headerAccessRequirements.toNonEmptyList.map(CompiledHeaderRequirementMatcher.compile))
  }

  private[http] final class CompiledHeaderRequirementMatcher private (
      val accessRequirement: AccessRequirement[Header],
      matches: Header => Boolean
  ) {

    def isFulfilledBy(requestHeaders: Set[Header]): Boolean =
      accessRequirement match {
        case AccessRequirement.MustBePresent(_) => requestHeaders.exists(matches)
        case AccessRequirement.MustBeAbsent(_)  => requestHeaders.forall(!matches(_))
      }

  }

  private[http] object CompiledHeaderRequirementMatcher {

    def compile(accessRequirement: AccessRequirement[Header]): CompiledHeaderRequirementMatcher = {
      val pattern = accessRequirement.value
      implicit val matchable: Matchable[String] = Matchable.caseSensitiveStringMatchable
      val valueMatcher = PatternsMatcher.create(pattern.value.value :: Nil)
      val matches: Header => Boolean =
        header => header.name === pattern.name && valueMatcher.`match`(header.value.value)
      new CompiledHeaderRequirementMatcher(accessRequirement, matches)
    }

  }

}
