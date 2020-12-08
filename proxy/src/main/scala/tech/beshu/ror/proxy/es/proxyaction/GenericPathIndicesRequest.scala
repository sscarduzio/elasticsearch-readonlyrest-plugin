package tech.beshu.ror.proxy.es.proxyaction

import monix.execution.atomic.Atomic
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.{ActionRequestValidationException, IndicesRequest}
import org.elasticsearch.rest.RestRequest

class GenericPathIndicesRequest(originIndices: List[String],
                                updateIndicesFunc: List[String] => RestRequest)
  extends ByProxyProcessedRequest with Replaceable {

  override val actionName: String = "proxy:pathindices"

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

  private val ilmExplainRegex = "^/?([^/]+)/_ilm/explain$".r
  private val ilmMoveToRegex = "^/?_ilm/move/([^/]+)$".r
  private val ilmRemovePolicyRegex = "^/?([^/]+)/_ilm/remove$".r

  def from(rest: RestRequest): Option[GenericPathIndicesRequest] = {
    val indicesStr = rest.path() match {
      case ilmExplainRegex(is) => Some(is)
      case ilmMoveToRegex(is) => Some(is)
      case ilmRemovePolicyRegex(is) => Some(is)
      case _ => None
    }
    indicesStr.map { str => new GenericPathIndicesRequest(splitIntoIndices(str), replace(str, rest))}
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