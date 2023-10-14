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

import tech.beshu.ror.accesscontrol.domain.{TemplateName, TemplateNamePattern}
import tech.beshu.ror.utils.MatcherWithWildcardsScala

// todo: what to do with this?
class TemplateNamePatternMatcher(templateNames: Set[TemplateNamePattern]) {
  val availableTemplatesMatcher: Matcher[TemplateNamePattern] =
    MatcherWithWildcardsScala.create[TemplateNamePattern](templateNames)

  def `match`(value: TemplateName): Boolean =
    TemplateNamePattern.fromString(value.value.value) match {
      case Some(t) => availableTemplatesMatcher.`match`(t)
      case None => false
    }
}

object TemplateNamePatternMatcher {
  def create(templateNames: Set[TemplateNamePattern]): TemplateNamePatternMatcher = {
    new TemplateNamePatternMatcher(templateNames)
  }
}