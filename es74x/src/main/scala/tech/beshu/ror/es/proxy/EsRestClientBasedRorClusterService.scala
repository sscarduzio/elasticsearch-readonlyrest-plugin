package tech.beshu.ror.es.proxy

import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.{AliasName, IndexName, IndexOrAlias, IndexPatten, IndexUuid, TemplateName}

import scala.collection.JavaConverters._

class EsRestClientBasedRorClusterService(client: RestHighLevelClient)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = ???

  override def allIndices: Set[IndexName] = ???

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    client
      .indices()
      .getAlias(new GetAliasesRequest(), RequestOptions.DEFAULT)
      .getAliases
      .asScala
      .toMap
      .mapValues(_.asScala.map(_.alias()).toSet)
  }

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = ???

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] = ???
}