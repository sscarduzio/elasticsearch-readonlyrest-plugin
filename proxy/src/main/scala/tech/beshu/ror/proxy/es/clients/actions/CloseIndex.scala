/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.close.{CloseIndexRequest => AdminCloseIndexRequest, CloseIndexResponse => AdminCloseIndexResponse}
import org.elasticsearch.client.indices.{CloseIndexRequest, CloseIndexResponse}
import org.elasticsearch.index.Index
import scala.collection.JavaConverters._

object CloseIndex {

  implicit class CloseIndexRequestOps(val request: AdminCloseIndexRequest) extends AnyVal {
    def toCloseIndexRequest: CloseIndexRequest = {
      new CloseIndexRequest(request.indices(): _*)
    }
  }

  implicit class CloseIndexResponseOps(val response: CloseIndexResponse) extends AnyVal {
    def toCloseAdminResponse: AdminCloseIndexResponse = new AdminCloseIndexResponse(
      response.isAcknowledged,
      response.isShardsAcknowledged,
      response
        .getIndices.asScala
        .map { result =>
          val index = new Index(result.getIndex, "")
          Option(result.getException) match {
            case Some(ex) =>
              new AdminCloseIndexResponse.IndexResult(index, ex)
            case None =>
              new AdminCloseIndexResponse.IndexResult(
                index,
                result.getShards.map { sr =>
                  new AdminCloseIndexResponse.ShardResult(
                    sr.getId,
                    sr.getFailures.map { f =>
                      new AdminCloseIndexResponse.ShardResult.Failure(f.index(), f.shardId(), f.getCause, f.getNodeId)
                    }
                  )
                }
              )
          }
        }
        .asJava
    )
  }
}
