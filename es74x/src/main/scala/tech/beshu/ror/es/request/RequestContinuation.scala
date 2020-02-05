package tech.beshu.ror.es.request

import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.rest.{BytesRestResponse, RestChannel}
import org.elasticsearch.tasks.Task

trait RequestContinuation {

  def proceed(request: ActionRequest,
              customListener: ActionListener[ActionResponse] => ActionListener[ActionResponse] = identity): Unit

  def fail(ex: Throwable): Unit

  def respondWith(response: ActionResponse): Unit

  def customResponse(createResponse: RestChannel => BytesRestResponse): Unit

}

class EsRequestContinuation(task: Task,
                            action: String,
                            baseListener: ActionListener[ActionResponse],
                            chain: ActionFilterChain[ActionRequest, ActionResponse],
                            channel: RestChannel)
  extends RequestContinuation {

  def proceed(request: ActionRequest,
              customListener: ActionListener[ActionResponse] => ActionListener[ActionResponse]): Unit = {
    chain.proceed(task, action, request, customListener(baseListener))
  }

  def fail(ex: Throwable): Unit = {
    baseListener.onFailure(new Exception(ex))
  }

  def respondWith(response: ActionResponse): Unit = {
    baseListener.onResponse(response)
  }

  def customResponse(createResponse: RestChannel => BytesRestResponse): Unit = {
    channel.sendResponse(createResponse(channel))
  }

}
