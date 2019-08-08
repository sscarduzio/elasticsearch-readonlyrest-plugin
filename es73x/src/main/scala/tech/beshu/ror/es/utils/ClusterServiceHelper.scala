package tech.beshu.ror.es.utils

import org.elasticsearch.cluster.metadata.MetaDataIndexTemplateService
import org.elasticsearch.cluster.service.ClusterService
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

object ClusterServiceHelper {

  def getIndicesRelatedToTemplates(clusterService: ClusterService, templateNames: Set[String]): Set[String] = {
    val indicesPatterns = templateNames.flatMap(getIndicesPatternsOfTemplate(clusterService, _))
    indicesFromPatterns(clusterService, indicesPatterns)
  }

  def indicesFromPatterns(clusterService: ClusterService, indicesPatterns: Set[String]): Set[String] = {
    val indicesMatcher = new MatcherWithWildcards(indicesPatterns.asJava)
    val allIndices = clusterService.state.getMetaData.getIndices.keysIt.asScala.toSet.asJava
    indicesMatcher.filter(allIndices).asScala.toSet
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
