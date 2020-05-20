/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.providers

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
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.boot.Ror
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService._

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

class EsIndexJsonContentProvider(client: NodeClient,
                                 ignore: Unit) // hack!
  extends IndexJsonContentService {

  @Inject
  def this(client: NodeClient) {
    this(client, ())
  }

  override def sourceOf(index: IndexName,
                        `type`: String,
                        id: String): Task[Either[ReadError, util.Map[String, _]]] = {
    Task
      .eval(getSourceCall(index.value.value, `type`, id))
      .map {
        case Success(value) => Right(value)
        case Failure(_: ResourceNotFoundException) => Left(ContentNotFound: ReadError)
        case Failure(_) => Left(CannotReachContentSource: ReadError)
      }
      .executeOn(Ror.blockingScheduler)
  }

  override def saveContent(index: IndexName,
                           `type`: String,
                           id: String,
                           content: util.Map[String, String]): Task[Either[WriteError, Unit]] = {
    val promise = CancelablePromise[Either[WriteError, Unit]]()
    client
      .prepareBulk
      .add(client.prepareIndex(index.value.value, `type`, id).setSource(content, XContentType.JSON).request)
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
