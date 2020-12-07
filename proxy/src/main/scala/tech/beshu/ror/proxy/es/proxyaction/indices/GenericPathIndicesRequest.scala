package tech.beshu.ror.proxy.es.proxyaction.indices

import monix.execution.atomic.Atomic
import org.elasticsearch.action.{ActionRequestValidationException, IndicesRequest}
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.proxy.es.proxyaction.{ByProxyProcessedRequest, ProxyRestRequest}

class GenericPathIndicesRequest(originIndices: List[String],
                                updateIndicesFunc: List[String] => RestRequest)
  extends ByProxyProcessedRequest with Replaceable {

  override val actionName: String = "proxy:pathindices"

  private val alteredIndices = Atomic(originIndices)

  override def indices(indices: String*): IndicesRequest = {
    alteredIndices.set(indices.toList)
    this
  }

  override def indices(): Array[String] = originIndices.toArray

  // todo:
  override def indicesOptions(): IndicesOptions = IndicesOptions.LENIENT_EXPAND_OPEN

  override def rest: RestRequest = updateIndicesFunc(alteredIndices.get())

  override def validate(): ActionRequestValidationException = null
}

object GenericPathIndicesRequest {

  private val ilmExplainRegex = "^/?([^/]+)/_ilm/explain$".r

  def from(rest: RestRequest): Option[GenericPathIndicesRequest] = {
    rest.path() match {
      case ilmExplainRegex(indicesStr) => Some(new GenericPathIndicesRequest(splitIntoIndices(indicesStr), replace(indicesStr, rest)))
      case _ => None
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