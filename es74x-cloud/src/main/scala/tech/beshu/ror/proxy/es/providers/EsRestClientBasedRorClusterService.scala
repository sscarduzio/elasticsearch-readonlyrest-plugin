/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.providers

import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData
import org.elasticsearch.common.regex.Regex
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._

import scala.collection.JavaConverters._

// todo: implement
// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = throw NotDefinedForRorProxy

  override def allIndices: Set[IndexName] = throw NotDefinedForRorProxy

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

  private def matchesIndexName(template: IndexTemplateMetaData, indexName: IndexName) = {
    template.patterns.stream.anyMatch((pattern: String) => Regex.simpleMatch(pattern, indexName))
  }
}