package tech.beshu.ror.es.proxy.es

import java.util

import monix.eval.Task
import org.elasticsearch.action.admin.cluster.health.{ClusterHealthRequest, ClusterHealthResponse}
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsResponse}
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.action.support.DefaultShardOperationFailedException
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.{GetAliasesResponse, RequestOptions, RestHighLevelClient}
import org.joor.Reflect.onClass

// todo: neat response handling when ES is not available (client throws connection error or times out)
// todo: use client async api
class RestHighLevelClientAdapter(client: RestHighLevelClient) {

  def search(request: SearchRequest): Task[SearchResponse] = {
    Task(client.search(request, RequestOptions.DEFAULT))
  }

  def health(request: ClusterHealthRequest): Task[ClusterHealthResponse] = {
    Task(client.cluster.health(request, RequestOptions.DEFAULT))
  }

  def getSettings(request: GetSettingsRequest): Task[GetSettingsResponse] = {
    Task(client.indices().getSettings(request, RequestOptions.DEFAULT))
  }

  def getAlias(request: GetAliasesRequest): Task[GetAliasesResponse] = {
    Task(client.indices().getAlias(request, RequestOptions.DEFAULT))
  }

  def stats(request: IndicesStatsRequest): Task[IndicesStatsResponse] = {
    Task(client.count(new CountRequest(), RequestOptions.DEFAULT))
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
}

