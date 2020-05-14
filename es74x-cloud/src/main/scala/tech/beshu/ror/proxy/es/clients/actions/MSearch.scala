package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.search.MultiSearchResponse
import tech.beshu.ror.proxy.es.exceptions._

object MSearch {

  implicit class MultiSearchResponseOps(val response: MultiSearchResponse) extends AnyVal {
    def toResponseWithSpecializedException: MultiSearchResponse = {
      val modifiedItems = response
        .getResponses
        .map { item =>
          Option(item.getFailure) match {
            case Some(ex) =>
              new MultiSearchResponse.Item(item.getResponse, ex.toSpecializedException)
            case None =>
              item
          }
        }
      new MultiSearchResponse(modifiedItems, response.getTook.millis())
    }
  }
}
