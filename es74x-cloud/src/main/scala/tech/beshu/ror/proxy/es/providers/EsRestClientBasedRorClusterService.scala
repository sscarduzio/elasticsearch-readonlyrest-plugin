package tech.beshu.ror.proxy.es.providers

import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

import scala.collection.JavaConverters._

// todo: implement
// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = ???

  override def allIndices: Set[IndexName] = ???

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    client
      .getAlias(new GetAliasesRequest())
      .map(_.getAliases.asScala.toMap.mapValues(_.asScala.map(_.alias()).toSet))
      .runSyncUnsafe()
  }

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = ???

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] = ???
}