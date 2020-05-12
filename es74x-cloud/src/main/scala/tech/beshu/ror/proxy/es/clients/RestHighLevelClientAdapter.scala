/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import java.util

import monix.eval.Task
import org.elasticsearch.action.admin.cluster.health.{ClusterHealthRequest, ClusterHealthResponse}
import org.elasticsearch.action.admin.cluster.storedscripts.{DeleteStoredScriptRequest, GetStoredScriptRequest, GetStoredScriptResponse, PutStoredScriptRequest}
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest => AdminGetIndexRequest, GetIndexResponse => AdminGetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsResponse.FieldMappingMetaData
import org.elasticsearch.action.admin.indices.mapping.get.{GetFieldMappingsRequest => AdminGetFieldMappingsRequest, GetFieldMappingsResponse => AdminGetFieldMappingsResponse, GetMappingsRequest => AdminGetMappingsRequest, GetMappingsResponse => AdminGetMappingsResponse}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest => AdminGetIndexTemplatesRequest, GetIndexTemplatesResponse => AdminGetIndexTemplatesResponse}
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.bulk.{BulkRequest, BulkResponse}
import org.elasticsearch.action.delete.{DeleteRequest, DeleteResponse}
import org.elasticsearch.action.fieldcaps.{FieldCapabilitiesRequest, FieldCapabilitiesResponse}
import org.elasticsearch.action.get._
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.action.main.{MainRequest, MainResponse}
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest, SearchResponse}
import org.elasticsearch.action.support.DefaultShardOperationFailedException
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.indices._
import org.elasticsearch.client.{GetAliasesResponse, RequestOptions, RestHighLevelClient}
import org.elasticsearch.cluster.ClusterName
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.compress.CompressedXContent
import org.elasticsearch.index.reindex.{BulkByScrollResponse, DeleteByQueryRequest, ReindexRequest, UpdateByQueryRequest}
import org.elasticsearch.script.mustache.{MultiSearchTemplateRequest, MultiSearchTemplateResponse, SearchTemplateRequest, SearchTemplateResponse}
import org.elasticsearch.{Build, ElasticsearchStatusException, Version}
import org.joor.Reflect.{on, onClass}
import tech.beshu.ror.proxy.es.exceptions._

import scala.collection.JavaConverters._

// todo: neat response handling when ES is not available (client throws connection error or times out)
// todo: use client async api
class RestHighLevelClientAdapter(client: RestHighLevelClient) {

  def main(request: MainRequest): Task[MainResponse] = {
    executeAsync(client.info(RequestOptions.DEFAULT))
      .map { response =>
        new MainResponse(
          response.getNodeName,
          Version.CURRENT,
          new ClusterName(response.getClusterName),
          response.getClusterUuid,
          Build.CURRENT
        )
      }
  }

  def getIndex(request: IndexRequest): Task[IndexResponse] = {
    executeAsync(client.index(request, RequestOptions.DEFAULT))
  }

  def deleteIndex(request: DeleteIndexRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().delete(request, RequestOptions.DEFAULT))
  }

  def getMappings(request: AdminGetMappingsRequest): Task[AdminGetMappingsResponse] = {
    val regularRequest = new GetMappingsRequest()
      .indices(request.indices(): _*)
      .local(request.local())
    executeAsync(client.indices().getMapping(regularRequest, RequestOptions.DEFAULT))
      .map { response =>
        val newMap = ImmutableOpenMap
          .builder()
          .putAll {
            response
              .mappings().asScala
              .map { case (key, value) =>
                val mappingsMap = ImmutableOpenMap
                  .builder()
                  .fPut("mappings", value)
                  .build()
                (key, mappingsMap)
              }
              .asJava
          }
          .build()
        new AdminGetMappingsResponse(newMap)
      }
  }

  def getFieldMappings(request: AdminGetFieldMappingsRequest): Task[AdminGetFieldMappingsResponse] = {
    val regularRequest = new GetFieldMappingsRequest()
      .fields(request.fields(): _*)
      .indices(request.indices(): _*)
      .local(request.local())
      .indicesOptions(request.indicesOptions())
    executeAsync(client.indices().getFieldMapping(regularRequest, RequestOptions.DEFAULT))
      .map { response =>
        val newMap = response
          .mappings().asScala
          .map { case (key, value) =>
            val newValue = value.asScala
              .map { case (innerKey, data) =>
                (innerKey, new FieldMappingMetaData(data.fullName(), on(data).call("getSource").get()))
              }
            (key, Map("mappings" -> newValue.asJava).asJava)
          }
          .asJava
        onClass(classOf[AdminGetFieldMappingsResponse])
          .create(newMap)
          .get[AdminGetFieldMappingsResponse]()
      }
  }

  def search(request: SearchRequest): Task[SearchResponse] = {
    executeAsync(client.search(request, RequestOptions.DEFAULT))
  }

  def mSearch(request: MultiSearchRequest): Task[MultiSearchResponse] = {
    executeAsync(client.msearch(request, RequestOptions.DEFAULT))
      .map { response =>
        val modifiedItems = response
          .getResponses
          .map { item =>
            Option(item.getFailure) match {
              case Some(ex) => ex
                .toIndexNotFoundException
                .map(new MultiSearchResponse.Item(item.getResponse, _))
                .getOrElse(item)
              case None =>
                item
            }
          }
        new MultiSearchResponse(modifiedItems, response.getTook.millis())
      }
  }

  def health(request: ClusterHealthRequest): Task[ClusterHealthResponse] = {
    executeAsync(client.cluster.health(request, RequestOptions.DEFAULT))
  }

  def getSettings(request: GetSettingsRequest): Task[GetSettingsResponse] = {
    executeAsync(client.indices().getSettings(request, RequestOptions.DEFAULT))
  }

  def getAlias(request: GetAliasesRequest): Task[GetAliasesResponse] = {
    executeAsync(client.indices().getAlias(request, RequestOptions.DEFAULT))
      .map { response =>
        Option(response.getException) match {
          case Some(ex) => ex
            .toIndexNotFoundException
            .map { ex =>
              onClass(classOf[GetAliasesResponse])
                .create(response.status(), ex)
                .get[GetAliasesResponse]()
            }
            .getOrElse(response)
          case None =>
            response
        }
      }
  }

  def get(request: GetRequest): Task[GetResponse] = {
    executeAsync(client.get(request, RequestOptions.DEFAULT))
  }

  def mGet(request: MultiGetRequest): Task[MultiGetResponse] = {
    executeAsync(client.mget(request, RequestOptions.DEFAULT))
      .map { response =>
        val modifiedItems = response
          .getResponses
          .map { item =>
            Option(item.getFailure) match {
              case Some(failure) => failure
                .getFailure
                .toIndexNotFoundException
                .map(ex => new MultiGetItemResponse(
                  item.getResponse,
                  new MultiGetResponse.Failure(failure.getIndex, item.getType, item.getId, ex)
                ))
                .getOrElse(item)
              case None =>
                item
            }
          }
        new MultiGetResponse(modifiedItems)
      }
  }

  def delete(request: DeleteRequest): Task[DeleteResponse] = {
    executeAsync(client.delete(request, RequestOptions.DEFAULT))
  }

  def bulk(request: BulkRequest): Task[BulkResponse] = {
    executeAsync(client.bulk(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: AdminGetIndexRequest): Task[AdminGetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: GetIndexRequest): Task[GetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def getTemplate(request: AdminGetIndexTemplatesRequest): Task[AdminGetIndexTemplatesResponse] = {
    val r = Option(request.names()) match {
      case Some(names) => new GetIndexTemplatesRequest(names: _*)
      case None => new GetIndexTemplatesRequest()
    }
    executeAsync(client.indices().getIndexTemplate(r, RequestOptions.DEFAULT))
      .map { response =>
        val metadataList = response
          .getIndexTemplates.asScala
          .map { metadata =>
            new IndexTemplateMetaData(
              metadata.name(),
              metadata.order(),
              metadata.version(),
              metadata.patterns(),
              metadata.settings(),
              Option(metadata.mappings()) match {
                case Some(mappings) =>
                  val builder = ImmutableOpenMap.builder[String, CompressedXContent]()
                  builder.put(mappings.`type`(), mappings.source())
                  builder.build()
                case None =>
                  ImmutableOpenMap.builder().build()
              },
              metadata.aliases()
            )
          }
        new AdminGetIndexTemplatesResponse(metadataList.asJava)
      }
      .onErrorRecover { case ex: ElasticsearchStatusException if ex.status().getStatus == 404 =>
        new AdminGetIndexTemplatesResponse(List.empty[IndexTemplateMetaData].asJava)
      }
  }

  def putTemplate(request: PutIndexTemplateRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().putTemplate(request, RequestOptions.DEFAULT))
  }

  def deleteTemplate(request: DeleteIndexTemplateRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().deleteTemplate(request, RequestOptions.DEFAULT))
  }

  def stats(request: IndicesStatsRequest): Task[IndicesStatsResponse] = {
    executeAsync(client.count(new CountRequest(), RequestOptions.DEFAULT))
      .map { resp =>
        // todo: better implementation needed
        onClass(classOf[IndicesStatsResponse])
          .create(
            new Array[org.elasticsearch.action.admin.indices.stats.ShardStats](0),
            new Integer(resp.getTotalShards),
            resp.getSuccessfulShards.asInstanceOf[Integer],
            resp.getFailedShards.asInstanceOf[Integer],
            new util.ArrayList[DefaultShardOperationFailedException]()
          )
          .get[IndicesStatsResponse]()
      }
  }

  def fieldCapabilities(request: FieldCapabilitiesRequest): Task[FieldCapabilitiesResponse] = {
    executeAsync(client.fieldCaps(request, RequestOptions.DEFAULT))
  }

  def deleteByQuery(request: DeleteByQueryRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.deleteByQuery(request, RequestOptions.DEFAULT))
  }

  def updateByQuery(request: UpdateByQueryRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.updateByQuery(request, RequestOptions.DEFAULT))
  }

  def putScript(request: PutStoredScriptRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.putScript(request, RequestOptions.DEFAULT))
  }

  def getScript(request: GetStoredScriptRequest): Task[GetStoredScriptResponse] = {
    executeAsync(client.getScript(request, RequestOptions.DEFAULT))
  }

  def deleteScript(request: DeleteStoredScriptRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.deleteScript(request, RequestOptions.DEFAULT))
  }

  def searchTemplate(request: SearchTemplateRequest): Task[SearchTemplateResponse] = {
    executeAsync(client.searchTemplate(request, RequestOptions.DEFAULT))
  }

  def mSearchTemplate(request: MultiSearchTemplateRequest): Task[MultiSearchTemplateResponse] = {
    executeAsync(client.msearchTemplate(request, RequestOptions.DEFAULT))
  }

  def reindex(request: ReindexRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.reindex(request, RequestOptions.DEFAULT))
  }

  private def executeAsync[T](action: => T): Task[T] = Task(action)
}

