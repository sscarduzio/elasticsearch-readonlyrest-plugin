/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContentObject}
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult.{Handled, PassItThrough}
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

class EsActionRequestHandler(esClient: RestHighLevelClientAdapter)
                            (implicit scheduler: Scheduler) {


  def handle(request: ActionRequest): Task[HandlingResult] = {
    tryToHandle
      .andThen(_.map[HandlingResult](Handled))
      .applyOrElse(request, (_: ActionRequest) => Task.now(PassItThrough)) // todo: pass through is not safe here
  }

  // todo: extend it
  private def tryToHandle: PartialFunction[ActionRequest, Task[ActionResponse with ToXContentObject]] = {
    case sr: SearchRequest => esClient.search(sr)
    case gr: GetRequest => esClient.get(gr)
  }

}

object EsActionRequestHandler {
  sealed trait HandlingResult
  object HandlingResult {
    final case class Handled(response: ActionResponse with ToXContentObject) extends HandlingResult
    case object PassItThrough extends HandlingResult
  }
}
