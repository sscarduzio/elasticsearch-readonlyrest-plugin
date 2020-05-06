/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import java.util

import monix.eval.Task
import org.elasticsearch.action.admin.cluster.health.{ClusterHealthRequest, ClusterHealthResponse}
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest => AdminGetIndexRequest, GetIndexResponse => AdminGetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsResponse.FieldMappingMetaData
import org.elasticsearch.action.admin.indices.mapping.get.{GetFieldMappingsRequest => AdminGetFieldMappingsRequest, GetFieldMappingsResponse => AdminGetFieldMappingsResponse, GetMappingsRequest => AdminGetMappingsRequest, GetMappingsResponse => AdminGetMappingsResponse}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest => AdminGetIndexTemplatesRequest, GetIndexTemplatesResponse => AdminGetIndexTemplatesResponse}
import org.elasticsearch.action.delete.{DeleteRequest, DeleteResponse}
import org.elasticsearch.action.fieldcaps.{FieldCapabilitiesRequest, FieldCapabilitiesResponse}
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest, SearchResponse}
import org.elasticsearch.action.support.DefaultShardOperationFailedException
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.indices._
import org.elasticsearch.client.{GetAliasesResponse, RequestOptions, RestHighLevelClient}
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.compress.CompressedXContent
import org.joor.Reflect.{on, onClass}
import tech.beshu.ror.proxy.es.exceptions.RorProxyException

import scala.collection.JavaConverters._

// todo: neat response handling when ES is not available (client throws connection error or times out)
// todo: use client async api
class RestHighLevelClientAdapter(client: RestHighLevelClient) {

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
  }

  def health(request: ClusterHealthRequest): Task[ClusterHealthResponse] = {
    executeAsync(client.cluster.health(request, RequestOptions.DEFAULT))
  }

  def getSettings(request: GetSettingsRequest): Task[GetSettingsResponse] = {
    executeAsync(client.indices().getSettings(request, RequestOptions.DEFAULT))
  }

  def getAlias(request: GetAliasesRequest): Task[GetAliasesResponse] = {
    executeAsync(client.indices().getAlias(request, RequestOptions.DEFAULT))
  }

  def get(request: GetRequest): Task[GetResponse] = {
    executeAsync(client.get(request, RequestOptions.DEFAULT))
  }

  def delete(request: DeleteRequest): Task[DeleteResponse] = {
    executeAsync(client.delete(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: AdminGetIndexRequest): Task[AdminGetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: GetIndexRequest): Task[GetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def getTemplate(request: AdminGetIndexTemplatesRequest): Task[AdminGetIndexTemplatesResponse] = {
    val r = new GetIndexTemplatesRequest(Option(request.names()).map(_.toList).getOrElse(Nil).asJava)
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
                  ImmutableOpenMap
                    .builder(mappings.getSourceAsMap.size())
                    .putAll(mappings.getSourceAsMap.asScala.mapValues(_.asInstanceOf[CompressedXContent]).asJava)
                    .build()
                case None =>
                  ImmutableOpenMap.builder().build()
              },
              metadata.aliases()
            )
          }
        new AdminGetIndexTemplatesResponse(metadataList.asJava)
      }
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

  private def executeAsync[T](action: => T): Task[T] = {
    Task(action)
    // todo: do we need it?
//      .onErrorRecoverWith { case ex =>
//        Task.raiseError(RorProxyException.wrap(ex))
//      }
  }
}

