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

import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.matchers.{Matchable, PatternsMatcher}

private[http] abstract class BaseHeaderRule
  extends RegularRule with Logging {

  protected def isFulfilled(accessRequirement: AccessRequirement[Header],
                            requestHeaders: Set[Header]): Boolean = {
    accessRequirement match {
      case AccessRequirement.MustBePresent(requiredHeader) =>
        requestHeaders.exists(matches(requiredHeader, _))
      case AccessRequirement.MustBeAbsent(forbiddenHeader) =>
        requestHeaders.forall(!matches(forbiddenHeader, _))
    }
  }

  private def matches(pattern: Header, header: Header) = {
    if (pattern.name === header.name) {
      implicit val matchable: Matchable[String] = Matchable.caseSensitiveStringMatchable
      PatternsMatcher
        .create(pattern.value.value :: Nil)
        .`match`(header.value.value)
    } else {
      false
    }
  }
}
