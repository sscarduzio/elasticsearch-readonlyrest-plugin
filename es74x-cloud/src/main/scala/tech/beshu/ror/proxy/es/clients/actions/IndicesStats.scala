/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import java.util

import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse
import org.elasticsearch.action.support.DefaultShardOperationFailedException
import org.elasticsearch.client.core.CountResponse
import org.joor.Reflect.onClass

object IndicesStats {

  implicit class IndicesStatsResponseOps(val response: CountResponse) extends AnyVal {

    // todo: better implementation needed
    def toIndicesStatsResponse: IndicesStatsResponse = {
      onClass(classOf[IndicesStatsResponse])
        .create(
          new Array[org.elasticsearch.action.admin.indices.stats.ShardStats](0),
          new Integer(response.getTotalShards),
          response.getSuccessfulShards.asInstanceOf[Integer],
          response.getFailedShards.asInstanceOf[Integer],
          new util.ArrayList[DefaultShardOperationFailedException]()
        )
        .get[IndicesStatsResponse]()
    }
  }
}
