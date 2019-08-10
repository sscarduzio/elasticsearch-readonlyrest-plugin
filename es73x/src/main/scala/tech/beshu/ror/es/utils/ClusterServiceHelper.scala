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

import org.elasticsearch.cluster.metadata.MetaDataIndexTemplateService
import org.elasticsearch.cluster.service.ClusterService
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

object ClusterServiceHelper {

  def getIndicesRelatedToTemplates(clusterService: ClusterService, templateNames: Set[String]): Set[String] = {
    val indicesPatterns = templateNames.flatMap(getIndicesPatternsOfTemplate(clusterService, _))
    indicesFromPatterns(clusterService, indicesPatterns).values.flatten.toSet
  }

  def indicesFromPatterns(clusterService: ClusterService, indicesPatterns: Set[String]): Map[String, Set[String]] = {
    val allIndices = clusterService.state.getMetaData.getIndices.keysIt.asScala.toSet.asJava
    indicesPatterns
        .map(p => (p, new MatcherWithWildcards(Set(p).asJava).filter(allIndices).asScala.toSet))
        .toMap
  }

  def getIndicesPatternsOfTemplate(clusterService: ClusterService, templateName: String): Set[String] = {
    Option(clusterService.state.getMetaData.templates.get(templateName))
      .toList
      .flatMap(_.patterns.asScala)
      .toSet
  }

  def findTemplatesOfIndices(clusterService: ClusterService, indices: Set[String]): Set[String] = {
    val metaData = clusterService.state.getMetaData
    indices
        .flatMap(index => MetaDataIndexTemplateService.findTemplates(metaData, index).asScala)
        .map(_.getName)
  }

}
