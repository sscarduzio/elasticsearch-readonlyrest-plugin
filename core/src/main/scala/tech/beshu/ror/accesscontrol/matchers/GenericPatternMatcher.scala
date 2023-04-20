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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.Pattern
import tech.beshu.ror.utils.CaseMappingEquality

class GenericPatternMatcher[T : CaseMappingEquality](patterns: Iterable[Pattern[T]]) {
  
  private val underlyingMatcher: Matcher[String] = {
    implicit val extractedStringCaseMappingEquality: CaseMappingEquality[String] = CaseMappingEquality.instance(
      identity,
      implicitly[CaseMappingEquality[T]].mapCases
    )
    MatcherWithWildcardsScalaAdapter[String](patterns.map(_.value.value).toSet)
  }

  def `match`(value: T): Boolean = {
    val stringValue = implicitly[CaseMappingEquality[T]].show(value)
    underlyingMatcher.`match`(stringValue)
  }
}
