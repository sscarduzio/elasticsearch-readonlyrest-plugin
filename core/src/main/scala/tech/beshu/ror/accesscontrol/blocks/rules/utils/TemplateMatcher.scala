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

import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringIndexNameNT
import tech.beshu.ror.accesscontrol.domain.IndexName

object TemplateMatcher {

  type OriginPatternName = IndexName
  type NarrowedPatternName = IndexName

  def filterAllowedTemplateIndexPatterns(templatePatterns: Set[OriginPatternName],
                                         allowedIndices: Set[IndexName]): Set[OriginPatternName] = {
    narrowAllowedTemplateIndexPatterns(templatePatterns, allowedIndices).map(_._1)
  }

  def narrowAllowedTemplateIndexPatterns(templatePatterns: Set[IndexName],
                                         allowedIndices: Set[IndexName]): Set[(OriginPatternName, NarrowedPatternName)] = {
    val allowedIndicesMatcher = new ScalaMatcherWithWildcards(allowedIndices)
    templatePatterns
      .flatMap { pattern =>
        val result = new ScalaMatcherWithWildcards(Set(pattern))
          .filterWithPatternMatched(allowedIndices)
          .map(_.swap)

        if (result.nonEmpty) result
        else allowedIndicesMatcher.`match`(pattern).map(_ => (pattern, pattern))
      }
  }

}
