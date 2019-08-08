package tech.beshu.ror.es

import java.util

import com.google.common.collect.Maps
import monix.eval.Task
import monix.execution.CancelablePromise
import org.elasticsearch.ResourceNotFoundException
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.boot.Ror
import tech.beshu.ror.es.IndexJsonContentManager._

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

class EsIndexJsonContentProvider(client: NodeClient,
                                 ignore: Unit) // hack!
  extends IndexJsonContentManager {

  @Inject
  def this(client: NodeClient) {
    this(client, ())
  }

  override def sourceOf(index: String,
                        `type`: String,
                        id: String): Task[Either[ReadError, util.Map[String, _]]] = {
    Task
      .eval(getSourceCall(index, `type`, id))
      .map {
        case Success(value) => Right(value)
        case Failure(_: ResourceNotFoundException) => Left(ContentNotFound: ReadError)
        case Failure(_) => Left(CannotReachContentSource: ReadError)
      }
      .executeOn(Ror.blockingScheduler)
  }

  override def saveContent(index: String,
                           `type`: String,
                           id: String,
                           content: util.Map[String, String]): Task[Either[WriteError, Unit]] = {
    val promise = CancelablePromise[Either[WriteError, Unit]]()
    client
      .prepareBulk
      .add(client.prepareIndex(index, `type`, id).setSource(content, XContentType.JSON).request)
      .execute(new PromiseActionListenerAdapter(promise))
    Task.fromCancelablePromise(promise)
  }

  private def getSourceCall(index: String, `type`: String, id: String): Try[util.Map[String, _]] = Try {
    val response = client.get(client.prepareGet(index, `type`, id).request).actionGet
    Option(response.getSourceAsMap).getOrElse(Maps.newHashMap())
  }

  private class PromiseActionListenerAdapter(promise: Promise[Either[WriteError, Unit]])
    extends ActionListener[BulkResponse] {

    override def onResponse(response: BulkResponse): Unit = promise.success(Right(()))

    override def onFailure(e: Exception): Unit = promise.success(Left(CannotWriteToIndex(e)))
  }
}
