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

import cats.implicits.*
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

private[http] abstract class BaseHeaderRule
  extends RegularRule {

  // The configured header requirements (and therefore their value-matching globs) are
  // static for the lifetime of the rule. Precompile each requirement's value matcher once
  // at construction instead of building a fresh single-element `PatternsMatcher` for every
  // (requirement x request header) pair on every request.
  protected def headerAccessRequirements: Iterable[AccessRequirement[Header]]

  // `lazy` because `headerAccessRequirements` is provided by a subclass `val`, which is not
  // yet initialized while this base-class constructor runs.
  private lazy val compiledMatcherByRequirement: Map[AccessRequirement[Header], Header => Boolean] =
    headerAccessRequirements.iterator.map(req => req -> matcherFor(req.value)).toMap

  protected def isFulfilled(accessRequirement: AccessRequirement[Header],
                            requestHeaders: Set[Header]): Boolean = {
    val matches = compiledMatcherByRequirement.getOrElse(accessRequirement, matcherFor(accessRequirement.value))
    accessRequirement match {
      case AccessRequirement.MustBePresent(_) => requestHeaders.exists(matches)
      case AccessRequirement.MustBeAbsent(_) => requestHeaders.forall(!matches(_))
    }
  }

  // Header-name comparison is case-insensitive (per `Header.Name`'s `Eq`) and the value
  // comparison is case-sensitive — exactly the original per-request `matches` semantics.
  private def matcherFor(pattern: Header): Header => Boolean = {
    implicit val matchable: Matchable[String] = Matchable.caseSensitiveStringMatchable
    val valueMatcher = PatternsMatcher.create(pattern.value.value :: Nil)
    header => header.name === pattern.name && valueMatcher.`match`(header.value.value)
  }
}
