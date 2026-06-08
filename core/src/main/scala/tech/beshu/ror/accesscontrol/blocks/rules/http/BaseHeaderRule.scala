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
import tech.beshu.ror.accesscontrol.blocks.rules.http.BaseHeaderRule.{CompiledRequirement, Settings}
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

abstract class BaseHeaderRule(val settings: Settings)
  extends RegularRule {

  // Each requirement's value matcher is compiled once at construction (the configured requirements
  // are static for the rule's lifetime) instead of building a fresh single-element `PatternsMatcher`
  // for every (requirement x request header) pair on every request. The pairing of a requirement
  // with its matcher is the only thing `isFulfilled` ever sees, so a "missing matcher" is impossible
  // by construction (no runtime lookup, no fallback).
  protected val compiledRequirements: NonEmptyList[CompiledRequirement] =
    settings.headerAccessRequirements.toNonEmptyList.map(CompiledRequirement.compile)

  protected def isFulfilled(requirement: CompiledRequirement, requestHeaders: Set[Header]): Boolean =
    requirement.accessRequirement match {
      case AccessRequirement.MustBePresent(_) => requestHeaders.exists(requirement.matches)
      case AccessRequirement.MustBeAbsent(_) => requestHeaders.forall(!requirement.matches(_))
    }
}

object BaseHeaderRule {

  final case class Settings(headerAccessRequirements: NonEmptySet[AccessRequirement[Header]])

  // A header requirement paired with its precompiled value matcher. Header-name comparison is
  // case-insensitive (per `Header.Name`'s `Eq`) and the value comparison is case-sensitive — exactly
  // the original per-request matching semantics.
  final case class CompiledRequirement(accessRequirement: AccessRequirement[Header], matches: Header => Boolean)
  object CompiledRequirement {
    def compile(accessRequirement: AccessRequirement[Header]): CompiledRequirement = {
      val pattern = accessRequirement.value
      implicit val matchable: Matchable[String] = Matchable.caseSensitiveStringMatchable
      val valueMatcher = PatternsMatcher.create(pattern.value.value :: Nil)
      val matches: Header => Boolean =
        header => header.name === pattern.name && valueMatcher.`match`(header.value.value)
      CompiledRequirement(accessRequirement, matches)
    }
  }
}
