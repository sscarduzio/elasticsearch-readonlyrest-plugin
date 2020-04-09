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
package tech.beshu.ror.es.utils

import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.{IndexPatten, TemplateName}

object ClusterServiceHelper {

  def getIndicesRelatedToTemplates(clusterService: RorClusterService,
                                   templateNames: Set[TemplateName]): Set[IndexName] = {
    val indicesPatterns = templateNames.flatMap(getIndicesPatternsOfTemplate(clusterService, _))
    indicesFromPatterns(clusterService, indicesPatterns).values.flatten.toSet
  }

  def indicesFromPatterns(clusterService: RorClusterService,
                          indicesPatterns: Set[IndexPatten]): Map[IndexPatten, Set[IndexName]] = {
    val allIndices = clusterService.allIndices
    indicesPatterns
      .map(p => (p, MatcherWithWildcardsScalaAdapter.create(Set(p)).filter(allIndices)))
      .toMap
  }

  def getIndicesPatternsOfTemplate(clusterService: RorClusterService,
                                   templateName: TemplateName): Set[IndexPatten] = {
    getIndicesPatternsOfTemplates(clusterService, Set(templateName))
  }

  def getIndicesPatternsOfTemplates(clusterService: RorClusterService,
                                    templateNames: Set[TemplateName]): Set[IndexPatten] = {
    clusterService
      .getTemplatesWithPatterns
      .filter(t => templateNames.contains(t._1))
      .flatMap(_._2)
      .toSet
  }

  def getIndicesPatternsOfTemplates(clusterService: RorClusterService): Set[IndexPatten] = {
    clusterService
      .getTemplatesWithPatterns
      .flatMap(_._2)
      .toSet
  }

}