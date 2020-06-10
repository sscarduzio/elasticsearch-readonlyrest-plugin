/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import cats.data.NonEmptyList
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.cluster.metadata.{AliasMetaData, IndexMetaData, IndexTemplateMetaData}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.Inaccessible
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template, TemplateName}
import tech.beshu.ror.accesscontrol.request.RequestContext
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

  //TODO
  override def verifyDocumentAccessibility(document: Document,
                                           filter: domain.Filter,
                                           id: RequestContext.Id): Task[domain.DocumentAccessibility] = {
    Task.now(Inaccessible)
  }

  //TODO
  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: domain.Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    Task.now(Map.empty)
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