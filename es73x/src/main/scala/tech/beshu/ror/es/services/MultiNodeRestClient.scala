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
package tech.beshu.ror.es.services

import cats.data.NonEmptyList
import org.elasticsearch.client.{Request, Response, ResponseListener, RestClient}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.FailoverClient.*
import tech.beshu.ror.es.utils.RestResponseOps.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import scala.collection.parallel.CollectionConverters.*
import scala.jdk.CollectionConverters.*


trait MultiNodeRestClient {
  def performRequestAsync(request: Request, responseListener: ResponseListener)(using RequestId): Unit

  def close(): Unit
}

final class RoundRobinClient(client: RestClient) extends MultiNodeRestClient {
  override def performRequestAsync(request: Request, responseListener: ResponseListener)(using RequestId): Unit = {
    client.performRequestAsync(request, responseListener)
  }

  override def close(): Unit = client.close()
}

final class FailoverClient private(nodeClients: NonEmptyList[NodeClient]) extends MultiNodeRestClient with RequestIdAwareLogging {

  private val backoffState = new ConcurrentHashMap[NodeId, BackoffState]()

  override def performRequestAsync(request: Request, responseListener: ResponseListener)(using RequestId): Unit = {
    val nodes = selectNodes()
    performWithFailover(nodes, request, responseListener)
  }

  override def close(): Unit = nodeClients.toList.par.foreach {
    _.restClient.close()
  }

  private def selectNodes(): NonEmptyList[NodeClient] = {
    // select nodes and perform failover
    val currentBackoffState = backoffState.asScala.toMap
    val (liveNodes, backoffNodes) = nodeClients.foldLeft((List.empty[NodeClient], List.empty[NodeState])) {
      case ((aliveList, backoffList), nodeClient) =>
        val maybeNodeBackoffState = currentBackoffState.get(nodeClient.id)
        maybeNodeBackoffState match {
          case Some(backoffState) if backoffState.isAlive =>
            (nodeClient :: aliveList, backoffList)
          case None =>
            (nodeClient :: aliveList, backoffList)
          case Some(backoffState) =>
            (aliveList, new NodeState(nodeClient, backoffState) :: backoffList)
        }
    }

    NonEmptyList.fromList(liveNodes.reverse)
      .getOrElse {
        // select client with the closest end
        NonEmptyList.one(backoffNodes.minBy(_.state).client)
      }
  }

  private def performWithFailover(nodes: NonEmptyList[NodeClient],
                                  request: Request,
                                  finalListener: ResponseListener,
                                 )(using RequestId): Unit = {
    nodes match {
      case NonEmptyList(nodeClient, Nil) =>
        nodeClient.restClient.performRequestAsync(
          request,
          new ResponseListener {
            override def onSuccess(response: Response): Unit = {
              onNodeSuccess(nodeClient.id, response)
              finalListener.onSuccess(response)
            }

            override def onFailure(exception: Exception): Unit = {
              onNodeFailure(nodeClient.id, exception)
              finalListener.onFailure(exception)
            }
          }
        )
      case NonEmptyList(nodeClient, nonEmptyOtherClients) =>
        nodeClient.restClient.performRequestAsync(
          request,
          new ResponseListener {
            override def onSuccess(response: Response): Unit = {
              onNodeSuccess(nodeClient.id, response)
              if (response.isRetryable) {
                performWithFailover(NonEmptyList.fromListUnsafe(nonEmptyOtherClients), request, finalListener)
              } else {
                finalListener.onSuccess(response)
              }
            }

            override def onFailure(exception: Exception): Unit = {
              onNodeFailure(nodeClient.id, exception)
              performWithFailover(NonEmptyList.fromListUnsafe(nonEmptyOtherClients), request, finalListener)
            }
          }
        )
    }
  }

  private def onNodeSuccess(nodeId: NodeId, response: Response)(using RequestId): Unit = {
    if (response.isRetryable) {
      logger.debug(s"Client with ID ${nodeId.value} returned retryable status code.")
      updateBackoffStateOnFailure(nodeId)
    } else {
      logger.debug(s"Client with ID ${nodeId.value} succeeded.")
      backoffState.remove(nodeId)
    }
  }

  private def onNodeFailure(nodeId: NodeId, exception: Exception)(using RequestId): Unit = {
    logger.debug(s"Client with ID ${nodeId.value} failed.", exception)
    exception match {
      case _: IOException => updateBackoffStateOnFailure(nodeId)
      case _ => ()
    }
  }

  private def updateBackoffStateOnFailure(nodeId: NodeId): Unit = {
    backoffState.compute(nodeId, (_, prev) =>
      Option(prev)
        .map(BackoffState.fromPrevious)
        .getOrElse(BackoffState.init())
    )
  }

}

object FailoverClient {

  def create(clients: NonEmptyList[RestClient]): FailoverClient = {
    val nodeClients = clients.zipWithIndex.map((restClient, idx) => new NodeClient(NodeId(idx), restClient))
    new FailoverClient(nodeClients)
  }

  private final case class NodeId(value: Int) extends AnyVal

  private final class NodeClient(val id: NodeId, val restClient: RestClient)

  private final class NodeState(val client: NodeClient, val state: BackoffState)

  private final case class BackoffState(failureCount: Int, deadUntilMillis: Long) {
    def isAlive: Boolean = System.currentTimeMillis() >= deadUntilMillis
  }

  private object BackoffState {
    given Ordering[BackoffState] = Ordering.by(_.deadUntilMillis)

    def init(): BackoffState = {
      init(failureCount = 1)
    }

    def fromPrevious(prev: BackoffState): BackoffState = {
      init(failureCount = prev.failureCount + 1)
    }

    private def init(failureCount: Int) = {
      BackoffState(failureCount, System.currentTimeMillis() + backoffMillis(failureCount))
    }

    private def backoffMillis(failureCount: Int): Long = {
      val delayInMillis = 1000.0 * math.pow(1.5, failureCount - 1)
      math.min(delayInMillis.toLong, MaxBackoffInMillis)
    }

    // the same as in RestClient
    private val MaxBackoffInMillis: Long = 30 * 60 * 1000L
  }
}
