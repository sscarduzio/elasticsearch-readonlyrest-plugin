/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.proxyaction

import monix.execution.atomic.Atomic
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.{ActionRequestValidationException, IndicesRequest}
import org.elasticsearch.rest.RestRequest

class GenericPathIndicesRequest(override val actionName: String,
                                originIndices: List[String],
                                updateIndicesFunc: List[String] => RestRequest)
  extends ByProxyProcessedRequest with Replaceable {

  private val alteredIndices = Atomic(originIndices)

  override def rest: RestRequest = updateIndicesFunc(alteredIndices.get())

  override def indices(indices: String*): IndicesRequest = {
    alteredIndices.set(indices.toList)
    this
  }

  override def indices(): Array[String] = originIndices.toArray

  // note: this should not be taken into consideration
  override def indicesOptions(): IndicesOptions = IndicesOptions.strictExpand()

  override def validate(): ActionRequestValidationException = null
}

object GenericPathIndicesRequest {

  private val ilmMoveToRegex = "^/?_ilm/move/([^/]+)$".r
  private val ilmExplainRegex = "^/?([^/]+)/_ilm/explain$".r
  private val ilmRemovePolicyRegex = "^/?([^/]+)/_ilm/remove$".r

  def from(rest: RestRequest): Option[GenericPathIndicesRequest] = {
    val indicesStrAndAction = rest.path() match {
      case ilmMoveToRegex(is) => Some((is, "cluster:admin/ilm/_move/post"))
      case ilmExplainRegex(is) => Some((is, "indices:admin/ilm/explain"))
      case ilmRemovePolicyRegex(is) => Some((is, "indices:admin/ilm/remove_policy"))
      case _ => None
    }
    indicesStrAndAction.map { case (str, action) =>
      new GenericPathIndicesRequest(action, splitIntoIndices(str), replace(str, rest))
    }
  }

  private def splitIntoIndices(indicesStr: String) = indicesStr.split(",").toList

  private def replace(indicesStr: String,
                      originRequest: RestRequest): List[String] => RestRequest = newIndices => {
    new ProxyRestRequest(
      originRequest.getXContentRegistry,
      originRequest.params(),
      originRequest.path().replace(indicesStr, newIndices.mkString(",")),
      originRequest.getHeaders,
      originRequest.getHttpRequest,
      originRequest.getHttpChannel
    )
  }
}