/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.server

import com.twitter.finagle.http.{Request, Response, Status, Version}
import com.twitter.finagle.{Http, Service}
import com.twitter.io.InputStreamReader
import com.twitter.util.Future
import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.rest.{RestRequest => EsRestRequest, RestResponse => EsRestResponse}
import tech.beshu.ror.proxy.es.{CreateEsHttpChannel, CreateEsHttpRequest, EsRestServiceSimulator}
import tech.beshu.ror.proxy.es.EsRestServiceSimulator.ProcessingResult
import tech.beshu.ror.utils.ScalaOps._

class ProxyRestInterceptorService(simulator: EsRestServiceSimulator)
                                 (implicit scheduler: Scheduler)
  extends Service[Request, Response] {

  private val client: Service[Request, Response] = Http.newService("localhost:9200") // todo: configuration

  override def apply(request: Request): Future[Response] = {
    simulator
      .processRequest(toEsRequest(request))
      .flatMap {
        case ProcessingResult.Response(response) =>
          Task.now(fromEsResponse(request.version, response))
        case ProcessingResult.PassThrough =>
          client.apply(request)
      }
  }

  private def toEsRequest(request: Request): EsRestRequest = EsRestRequest.request(
    NamedXContentRegistry.EMPTY,
    esHttpRequestFrom(request),
    CreateEsHttpChannel.dummyHttpChannel
  )

  private def fromEsResponse(version: Version, response: EsRestResponse): Response =
    Response(
      version,
      Status.fromCode(response.status().getStatus),
      InputStreamReader(response.content().streamInput())
    )

  private def esHttpRequestFrom(request: Request) = CreateEsHttpRequest.from(request)
}
