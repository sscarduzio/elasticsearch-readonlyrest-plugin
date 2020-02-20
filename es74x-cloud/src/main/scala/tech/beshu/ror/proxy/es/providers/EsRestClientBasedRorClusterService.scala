/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.providers

import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.cluster.metadata.IndexMetaData
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

import scala.collection.JavaConverters._

// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    client
      .getIndex(new GetIndexRequest(indexOrAlias))
      .map { response =>
        Option(response.getSetting(indexOrAlias, IndexMetaData.INDEX_UUID_NA_VALUE)).toSet
      }
      .runSyncUnsafe()
  }

  override def allIndices: Set[IndexName] = {
    client
      .getIndex(new GetIndexRequest())
      .map(_.getIndices.toSet)
      .runSyncUnsafe()
  }

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    client
      .getAlias(new GetAliasesRequest())
      .map(_.getAliases.asScala.toMap.mapValues(_.asScala.map(_.alias()).toSet))
      .runSyncUnsafe()
  }

  override def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName] = {
    getTemplatesMetadata
      .map { templates =>
        val templateIndexMatchers = templates
          .map(t => (t.getName, MatcherWithWildcardsScalaAdapter.create(t.patterns().asScala)))
          .toMap
        indices.flatMap { index =>
          templateIndexMatchers
            .filter { case (_, matcher) => matcher.`match`(index) }
            .keys
        }
      }
      .runSyncUnsafe()
  }

  override def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]] = {
    getTemplatesMetadata
      .map { templates =>
        templates
          .map { metadata => (metadata.name(), metadata.patterns().asScala.toSet) }
          .toMap
      }
      .runSyncUnsafe()
  }

  private def getTemplatesMetadata = {
    client
      .getTemplate(new GetIndexTemplatesRequest())
      .map(_.getIndexTemplates.asScala.toList)
  }

}