/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.genericaction

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest

class GenericRequest(val rest: RestRequest) extends ActionRequest {
  override def validate(): ActionRequestValidationException = null
}

object GenericRequest {

  def from(rest: RestRequest): Option[GenericRequest] = {
    allowedRequestsStartingPaths
      .find(rest.uri().startsWith)
      .map(_ => new GenericRequest(rest))
  }

  private val allowedRequestsStartingPaths = Set(
    "/_xpack",
    "/_security",
    "/_monitoring",
    "/_nodes"
  )
}