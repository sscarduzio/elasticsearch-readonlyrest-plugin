/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.proxyaction

import org.elasticsearch.action.ActionRequestValidationException
import org.elasticsearch.rest.RestRequest

class GenericRequest(override val actionName: String,
                     val rest: RestRequest)
  extends ByProxyProcessedRequest {
  override def validate(): ActionRequestValidationException = null
}

object GenericRequest {

  private val ilmPolicyManagement = "^/?_ilm/policy/([^/]+)/?$".r
  private val ilmPolicyStart = "^/?_ilm/start/?$".r
  private val ilmPolicyStop = "^/?_ilm/stop/?$".r
  private val ilmStatus = "^/?_ilm/status/?$".r

  def from(rest: RestRequest): Option[GenericRequest] = {
    val foundAction = rest.path() match {
      case ilmPolicyManagement(_) =>
        rest.method() match {
          case RestRequest.Method.GET => Some("cluster:admin/ilm/get")
          case RestRequest.Method.PUT => Some("cluster:admin/ilm/put")
          case RestRequest.Method.DELETE => Some("cluster:admin/ilm/delete")
          case _ => None
        }
      case ilmPolicyStart() => Some("cluster:admin/ilm/start")
      case ilmPolicyStop() => Some("cluster:admin/ilm/stop")
      case ilmStatus() => Some("cluster:admin/ilm/operation_mode/get")
      case _ => None
    }
    foundAction match {
      case Some(action) => Some(new GenericRequest(action, rest))
      case None if isAllowedByPath(rest).isDefined => Some(new GenericRequest("proxy:generic", rest))
      case None => None
    }
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
