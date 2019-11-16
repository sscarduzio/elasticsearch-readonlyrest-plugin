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

import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.{IndexName, IndexPatten, TemplateName}
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

object ClusterServiceHelper {

  def getIndicesRelatedToTemplates(clusterService: RorClusterService,
                                   templateNames: Set[TemplateName]): Set[IndexName] = {
    val indicesPatterns = templateNames.flatMap(getIndicesPatternsOfTemplate(clusterService, _))
    indicesFromPatterns(clusterService, indicesPatterns).values.flatten.toSet
  }

  def indicesFromPatterns(clusterService: RorClusterService,
                          indicesPatterns: Set[IndexPatten]): Map[IndexPatten, Set[IndexName]] = {
    val allIndices = clusterService.allIndices.asJava
    indicesPatterns
      .map(p => (p, new MatcherWithWildcards(Set(p).asJava).filter(allIndices).asScala.toSet))
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