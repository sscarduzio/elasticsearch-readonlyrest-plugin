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

  // todo: maybe not needed
  def narrowAllowedTemplatesIndicesPatterns(templatesPatterns: Set[IndexName],
                                            allowedIndices: Set[IndexName]): Set[IndexName] = {
    val allowedIndicesMatcher = MatcherWithWildcardsScalaAdapter.create(allowedIndices)
    templatesPatterns
      .flatMap { pattern =>
        val result = MatcherWithWildcardsScalaAdapter
          .create(Set(pattern))
          .filter(allowedIndices)
        if (result.nonEmpty) result
        else if (allowedIndicesMatcher.`match`(pattern)) Set(pattern)
        else Set.empty[IndexName]
      }
  }

  def filterAllowedTemplatesIndicesPatterns(templatesPatterns: Set[IndexName],
                                            allowedIndices: Set[IndexName]): Set[IndexName] = {
    val result = new ScalaMatcherWithWildcards(templatesPatterns)
      .filterWithPatternMatched(allowedIndices)
      .map(_._2)
    if (result.isEmpty) new ScalaMatcherWithWildcards(allowedIndices).filter(templatesPatterns)
    else result
  }

}
