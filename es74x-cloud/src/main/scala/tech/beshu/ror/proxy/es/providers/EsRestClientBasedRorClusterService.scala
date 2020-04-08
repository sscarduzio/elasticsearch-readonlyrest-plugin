/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.providers

import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy

import scala.collection.JavaConverters._

// todo: implement
// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = throw NotDefinedForRorProxy

  override def allIndices: Set[IndexName] = throw NotDefinedForRorProxy

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
//    client
//      .getAlias(new GetAliasesRequest())
//      .map(_.getAliases.asScala.toMap.mapValues(_.asScala.map(_.alias()).toSet))
//      .runSyncUnsafe()
    ???  /// todo: fixme
  }

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = throw NotDefinedForRorProxy

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] = throw NotDefinedForRorProxy

  override def expandIndices(indices: Set[es.RorClusterService.AliasName]): Set[es.RorClusterService.AliasName] = ???
}