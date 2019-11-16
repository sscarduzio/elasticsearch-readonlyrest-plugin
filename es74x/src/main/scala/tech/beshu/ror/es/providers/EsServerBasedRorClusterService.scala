package tech.beshu.ror.es.providers
import org.elasticsearch.cluster.metadata.MetaDataIndexTemplateService
import org.elasticsearch.cluster.service.ClusterService
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.{AliasName, IndexName, IndexOrAlias, IndexPatten, IndexUuid, TemplateName}

import scala.collection.JavaConverters._

class EsServerBasedRorClusterService(clusterService: ClusterService) extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndices: Set[IndexName] = clusterService.state.getMetaData.getIndices.keysIt.asScala.toSet

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = {
    val metaData = clusterService.state.getMetaData
    indices
      .flatMap(index => MetaDataIndexTemplateService.findTemplates(metaData, index).asScala)
      .map(_.getName)
  }

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] =
    Option(clusterService.state.getMetaData.templates)
      .toList
      .flatMap(t => t.iterator().asScala.map(t => (t.key, t.value.patterns().asScala.toSet)))
      .toMap

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    val indices = clusterService.state.metaData.getIndices
    indices
      .keysIt().asScala
      .map { index =>
        val indexMetaData = indices.get(index)
        val indexName = indexMetaData.getIndex.getName
        val aliases: Set[String] = indexMetaData.getAliases.keysIt.asScala.toSet
        (indexName, aliases)
      }
      .toMap
  }
}