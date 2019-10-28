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

  type IndexPatten = String
  type IndexName = String
  type TemplateName = String

  def getIndicesRelatedToTemplates(clusterService: ClusterService, templateNames: Set[TemplateName]): Set[IndexName] = {
    val indicesPatterns = templateNames.flatMap(getIndicesPatternsOfTemplate(clusterService, _))
    indicesFromPatterns(clusterService, indicesPatterns).values.flatten.toSet
  }

  def indicesFromPatterns(clusterService: ClusterService, indicesPatterns: Set[IndexPatten]): Map[IndexPatten, Set[IndexName]] = {
    val allIndices = clusterService.state.getMetaData.getIndices.keysIt.asScala.toSet.asJava
    indicesPatterns
        .map(p => (p, new MatcherWithWildcards(Set(p).asJava).filter(allIndices).asScala.toSet))
        .toMap
  }

  def getIndicesPatternsOfTemplate(clusterService: ClusterService, templateName: TemplateName): Set[IndexPatten] = {
    getIndicesPatternsOfTemplates(clusterService, Set(templateName))
  }

  def getIndicesPatternsOfTemplates(clusterService: ClusterService, templateNames: Set[TemplateName]): Set[IndexPatten] = {
    getTemplatesWithPatterns(clusterService)
      .filter(t => templateNames.contains(t._1))
      .flatMap(_._2)
  }

  def getIndicesPatternsOfTemplates(clusterService: ClusterService): Set[IndexPatten] = {
    getTemplatesWithPatterns(clusterService).flatMap(_._2)
  }

  private def getTemplatesWithPatterns(clusterService: ClusterService): Set[(TemplateName, Set[IndexPatten])] = {
    Option(clusterService.state.getMetaData.templates)
      .toList
      .flatMap(t => t.iterator().asScala.map(t => (t.key, t.value.patterns().asScala.toSet)))
      .toSet
  }

  def findTemplatesOfIndices(clusterService: ClusterService, indices: Set[IndexName]): Set[IndexName] = {
    val metaData = clusterService.state.getMetaData
    indices
        .flatMap(index => MetaDataIndexTemplateService.findTemplates(metaData, index).asScala)
        .map(_.getName)
  }

}
