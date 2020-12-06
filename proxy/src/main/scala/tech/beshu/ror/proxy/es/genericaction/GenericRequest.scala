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
    isAllowedByPath(rest) map (_ => new GenericRequest(rest))
  }

  private def isAllowedByPath(rest: RestRequest) = {
    allowedPathParts.find(rest.uri().contains)
  }

  private val allowedPathParts = Set(
    "/_autoscaling",
    "/_cat",
    "_cat/transforms",
    "/_cluster", // todo: see ClusterApiProxyTests
    "/_ccr/",
    "/_enrich",
    "/_freeze", // todo: indices awareness
    "/_ilm/", // todo: indices awareness
    "/_license",
    "/_monitoring",
    "/_ml/",
    "/_nodes",
    "/_rollup/",
    "/_security",
    "/_transform",
    "/_unfreeze", // todo: indices awareness,
    "/_async_search", // todo: indices awareness,
    "/_watcher",
    "/_xpack",
  )

}
