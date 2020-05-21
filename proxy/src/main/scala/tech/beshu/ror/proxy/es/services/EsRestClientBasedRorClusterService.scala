/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import cats.implicits._
import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.cluster.metadata.{AliasMetaData, IndexMetaData, IndexTemplateMetaData}
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    client
      .getIndex(new GetIndexRequest(indexOrAlias.value.value))
      .map { response =>
        Option(response.getSetting(indexOrAlias.value.value, IndexMetaData.INDEX_UUID_NA_VALUE)).toSet
      }
      .runSyncUnsafe()
  }

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    client
      .getAlias(new GetAliasesRequest())
      .map { response =>
        response
          .getAliases.asScala
          .flatMap { case (indexNameString, aliases) =>
            indexWithAliasesFrom(indexNameString, aliases.asScala.toSet)
          }
          .toMap
      }
      .runSyncUnsafe()
  }

  override def allTemplates: Set[Template] = {
    client
      .getTemplate(new GetIndexTemplatesRequest())
      .map { response =>
        response
          .getIndexTemplates.asScala
          .flatMap(templateFrom)
          .toSet
      }
      .runSyncUnsafe()
  }

  override def getTemplate(name: TemplateName): Option[Template] = {
    allTemplates.find(_.name === name)
  }

  private def indexWithAliasesFrom(indexNameString: String, aliasMetadata: Set[AliasMetaData]) = {
    IndexName
      .fromString(indexNameString)
      .map { index =>
        (index, aliasMetadata.flatMap(am => IndexName.fromString(am.alias())))
      }
  }

  private def templateFrom(metaData: IndexTemplateMetaData): Option[Template] = {
    TemplateName
      .fromString(metaData.name())
      .flatMap { templateName =>
        UniqueNonEmptyList
          .fromList {
            metaData
              .patterns().asScala.toList
              .flatMap(IndexName.fromString)
          }
          .map { patterns =>
            Template(templateName, patterns)
          }
      }
  }
}