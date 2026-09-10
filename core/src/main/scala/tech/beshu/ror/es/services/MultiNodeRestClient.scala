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
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.FailoverClient.*
import tech.beshu.ror.es.services.MultiNodeRestClient.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

trait MultiNodeRestClient[Req, Resp] {

  def perform(request: Req)(
      using RequestId
  ): Task[Resp]

  def close(): Unit
}

object MultiNodeRestClient {

  trait RequestExecutor[Req, Resp] {
    def execute(request: Req): Task[Resp]

    def close(): Unit
  }

  sealed trait FailoverDecision

  object FailoverDecision {
    case object TryNextNode extends FailoverDecision

    case object Stop extends FailoverDecision
  }

}

/**
 * Passes each request to the given executor. The executor holds one client that knows all the cluster nodes, so the
 * client, not this class, decides which node gets the request.
 */
final class DelegatingMultiNodeRestClient[Req, Resp](executor: RequestExecutor[Req, Resp])
    extends MultiNodeRestClient[Req, Resp] {

  override def perform(request: Req)(
      using RequestId
  ): Task[Resp] = executor.execute(request)

  override def close(): Unit = executor.close()
}

final class FailoverClient[Req, Resp] private (
    nodeClients: NonEmptyList[NodeClient[Req, Resp]],
    failoverDecision: Throwable => FailoverDecision,
    clock: Clock
) extends MultiNodeRestClient[Req, Resp]
    with RequestIdAwareLogging {

  private val openCircuits = new ConcurrentHashMap[NodeId, OpenCircuit]()

  override def perform(request: Req)(
      using RequestId
  ): Task[Resp] = Task.defer(performWithFailover(selectNodes(), request))

  override def close(): Unit = nodeClients.toList.foreach {
    _.executor.close()
  }

  private def selectNodes(): NonEmptyList[NodeClient[Req, Resp]] = {
    val currentOpenCircuits = openCircuits.asScala.toMap
    val (availableNodes, unavailableNodes) =
      nodeClients.foldLeft((List.empty[NodeClient[Req, Resp]], List.empty[(NodeClient[Req, Resp], OpenCircuit)])) {
        case ((availableList, unavailableList), nodeClient) =>
          currentOpenCircuits.get(nodeClient.id) match {
            case Some(openCircuit) if openCircuit.allowsTrialRequest(clock) =>
              (nodeClient :: availableList, unavailableList)
            case None =>
              (nodeClient :: availableList, unavailableList)
            case Some(openCircuit) =>
              (availableList, (nodeClient, openCircuit) :: unavailableList)
          }
      }

    NonEmptyList
      .fromList(availableNodes.reverse)
      .getOrElse {
        // all circuits are open - try the node whose circuit allows a trial request soonest
        NonEmptyList.one(unavailableNodes.minBy(_._2)._1)
      }
  }

  private def performWithFailover(
      nodes: NonEmptyList[NodeClient[Req, Resp]],
      request: Req
  )(
      using RequestId
  ): Task[Resp] = {
    nodes match {
      case NonEmptyList(nodeClient, Nil) =>
        perform(nodeClient, request).onErrorHandleWith { exception =>
          onNodeFailure(nodeClient.id, exception)
          Task.raiseError(exception)
        }
      case NonEmptyList(nodeClient, nonEmptyOtherClients) =>
        perform(nodeClient, request).onErrorHandleWith { exception =>
          onNodeFailure(nodeClient.id, exception) match {
            case FailoverDecision.TryNextNode =>
              performWithFailover(NonEmptyList.fromListUnsafe(nonEmptyOtherClients), request)
            case FailoverDecision.Stop =>
              Task.raiseError(exception)
          }
        }
    }
  }

  private def perform(nodeClient: NodeClient[Req, Resp], request: Req)(
      using RequestId
  ): Task[Resp] = {
    nodeClient.executor.execute(request).map { response =>
      onNodeSuccess(nodeClient.id)
      response
    }
  }

  private def onNodeSuccess(nodeId: NodeId)(
      using RequestId
  ): Unit = {
    logger.debug(s"Client with ID ${nodeId.value} succeeded.")
    openCircuits.remove(nodeId)
  }

  private def onNodeFailure(nodeId: NodeId, exception: Throwable)(
      using RequestId
  ): FailoverDecision = {
    logger.debug(s"Client with ID ${nodeId.value} failed.", exception)
    failoverDecision(exception) match {
      case FailoverDecision.TryNextNode =>
        openCircuitOnFailure(nodeId)
        FailoverDecision.TryNextNode
      case FailoverDecision.Stop =>
        FailoverDecision.Stop
    }
  }

  private def openCircuitOnFailure(nodeId: NodeId): Unit = {
    openCircuits.compute(
      nodeId,
      (_, prev) =>
        Option(prev)
          .map(OpenCircuit.afterAnotherFailure(_, clock))
          .getOrElse(OpenCircuit.afterFirstFailure(clock))
    )
  }

}

object FailoverClient {

  def create[Req, Resp](
      nodeExecutors: NonEmptyList[RequestExecutor[Req, Resp]],
      failoverDecision: Throwable => FailoverDecision,
      clock: Clock
  ): FailoverClient[Req, Resp] = {
    val nodeClients = nodeExecutors.zipWithIndex.map((executor, idx) => new NodeClient(NodeId(idx), executor))
    new FailoverClient(nodeClients, failoverDecision, clock)
  }

  private final case class NodeId(value: Int) extends AnyVal

  private final class NodeClient[Req, Resp](val id: NodeId, val executor: RequestExecutor[Req, Resp])

  private final case class OpenCircuit(failureCount: Int, allowTrialRequestAtMillis: Long) {
    def allowsTrialRequest(clock: Clock): Boolean = clock.millis() >= allowTrialRequestAtMillis
  }

  private object OpenCircuit {
    given Ordering[OpenCircuit] = Ordering.by(_.allowTrialRequestAtMillis)

    def afterFirstFailure(clock: Clock): OpenCircuit = {
      create(failureCount = 1, clock)
    }

    def afterAnotherFailure(prev: OpenCircuit, clock: Clock): OpenCircuit = {
      create(failureCount = prev.failureCount + 1, clock)
    }

    private def create(failureCount: Int, clock: Clock) = {
      OpenCircuit(failureCount, clock.millis() + openDurationMillis(failureCount))
    }

    private def openDurationMillis(failureCount: Int): Long = {
      val delayInMillis = 1000.0 * math.pow(1.5, failureCount - 1)
      math.min(delayInMillis.toLong, MaxOpenDurationInMillis)
    }

    // the same as in RestClient
    private val MaxOpenDurationInMillis: Long = 30 * 60 * 1000L
  }

}
