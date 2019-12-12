/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

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
import tech.beshu.ror.proxy.es.exceptions.RorProxyException

// todo: neat response handling when ES is not available (client throws connection error or times out)
// todo: use client async api
class RestHighLevelClientAdapter(client: RestHighLevelClient) {

  def search(request: SearchRequest): Task[SearchResponse] = {
    executeAsync(client.search(request, RequestOptions.DEFAULT))
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

  private def executeAsync[T](action: => T): Task[T] = {
    Task(action).onErrorRecoverWith { case ex => Task.raiseError(RorProxyException.wrap(ex)) }
  }
}

