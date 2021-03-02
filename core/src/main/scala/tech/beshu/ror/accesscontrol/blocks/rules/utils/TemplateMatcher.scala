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
package tech.beshu.ror.accesscontrol.blocks.rules.utils

import tech.beshu.ror.accesscontrol.domain.{Template, TemplateNamePattern}

// todo:
class TemplateMatcher(namePatterns: Set[TemplateNamePattern]) {

  private val matcher = MatcherWithWildcardsScalaAdapter.create(namePatterns)

  def filterTemplates[T <: Template](templates: Set[T]): Set[T] = {
    val templateByName: Map[TemplateNamePattern, Set[T]] = templates.groupBy(t => TemplateNamePattern(t.name.value))
    val filteredTemplateNames = matcher.filter(templateByName.keys.toSet)
    templateByName.filterKeys(filteredTemplateNames.contains).values.toSet.flatten
  }

  def filterTemplateNamePatterns(namePatterns: Set[TemplateNamePattern]): Set[TemplateNamePattern] = {
    matcher.filter(namePatterns)
  }
}
