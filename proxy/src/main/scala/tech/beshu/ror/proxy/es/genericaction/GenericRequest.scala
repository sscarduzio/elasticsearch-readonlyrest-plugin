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
    isAllowedByInfix(rest) orElse isAllowedByPrefix(rest) map (_ => new GenericRequest(rest))
  }

  private def isAllowedByPrefix(rest: RestRequest) = {
    allowedPathPrefixes.find(rest.uri().startsWith)
  }

  private def isAllowedByInfix(rest: RestRequest) = {
    allowedPathInfixes.find(rest.uri().contains)
  }

  private val allowedPathPrefixes = Set(
    "/_xpack",
    "/_security",
    "/_monitoring",
    "/_nodes",
    "/_cluster",
    "/_cat",
    "/_transform",
    "/_license"
  )

  private val allowedPathInfixes = Set(
    "/_rollup/",
    "/_ccr/",
    "/_ilm/"
  )
}