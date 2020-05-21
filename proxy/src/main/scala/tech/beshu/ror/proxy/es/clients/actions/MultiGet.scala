/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.get.{MultiGetItemResponse, MultiGetResponse}
import tech.beshu.ror.proxy.es.exceptions._

object MultiGet {

  implicit class MultiGetResponseOps(val response: MultiGetResponse) extends AnyVal {
    def toResponseWithSpecializedException: MultiGetResponse = {
      val modifiedItems = response
        .getResponses
        .map { item =>
          Option(item.getFailure) match {
            case Some(failure) =>
              new MultiGetItemResponse(
                item.getResponse,
                new MultiGetResponse.Failure(failure.getIndex, item.getType, item.getId, failure.getFailure.toSpecializedException)
              )
            case None =>
              item
          }
        }
      new MultiGetResponse(modifiedItems)
    }
  }
}
