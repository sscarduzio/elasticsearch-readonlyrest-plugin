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
import cats.effect.Clock as CatsClock
import monix.catnap.CircuitBreaker
import monix.eval.Task
import monix.execution.exceptions.ExecutionRejectedException
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.FailoverClient.*
import tech.beshu.ror.es.services.MultiNodeRestClient.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Clock
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

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

  trait FailoverAwareRequestExecutor[Req, Resp] extends RequestExecutor[Req, Resp] {

    /**
     * Tells if the given failure of this executor allows a failover to another node. The method must be pure.
     */
    def failoverDecisionOn(exception: Throwable): FailoverDecision
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
    clock: Clock
) extends MultiNodeRestClient[Req, Resp]
    with RequestIdAwareLogging {

  override def perform(request: Req)(
      using RequestId
  ): Task[Resp] =
    selectNodes().flatMap {
      case NodesToTry.WithClosedOrExpiredCircuit(nodes) =>
        performWithFailover(nodes, request)
      case NodesToTry.OnlyWithOpenCircuits(nodeClient) =>
        performIgnoringCircuit(nodeClient, request)
    }

  override def close(): Unit = nodeClients.toList.foreach {
    _.executor.close()
  }

  private def selectNodes(): Task[NodesToTry[Req, Resp]] = {
    nodeClients
      .traverse(nodeClient => nodeClient.circuitBreaker.state.map((nodeClient, _)))
      .map { nodesWithCircuitState =>
        val now = clock.millis()
        val (unavailableNodes, availableNodes) = nodesWithCircuitState.toList.partitionMap {
          case (nodeClient, openCircuit: CircuitBreaker.Open) if openCircuit.expiresAt > now =>
            Left((nodeClient, openCircuit))
          case (nodeClient, _) =>
            Right(nodeClient)
        }
        NonEmptyList
          .fromList(availableNodes)
          .map(NodesToTry.WithClosedOrExpiredCircuit.apply)
          .getOrElse {
            // all circuits are open - try the node whose circuit expires soonest
            NodesToTry.OnlyWithOpenCircuits(unavailableNodes.minBy(_._2.expiresAt)._1)
          }
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
        performProtectedByCircuitBreaker(nodeClient, request)
          .flatMap(Task.fromEither)
      case NonEmptyList(nodeClient, nextNode :: otherNodes) =>
        performProtectedByCircuitBreaker(nodeClient, request)
          .onErrorHandleWith { exception =>
            // only a node failure or a rejection by an open circuit is raised here, and both allow a failover
            logRejectionByOpenCircuit(nodeClient.id, exception)
            performWithFailover(NonEmptyList(nextNode, otherNodes), request).map(Right(_))
          }
          .flatMap(Task.fromEither)
    }
  }

  /**
   * A Left holds a failure which does not allow a failover. A raised error is a node failure or a rejection by an open
   * circuit, and both allow a failover.
   */
  private def performProtectedByCircuitBreaker(nodeClient: NodeClient[Req, Resp], request: Req)(
      using RequestId
  ): Task[Either[Throwable, Resp]] = {
    nodeClient.circuitBreaker
      .protect {
        perform(nodeClient, request)
          .map(Right(_): Either[Throwable, Resp])
          .onErrorHandleWith { exception =>
            logger.debug(s"Client with ID ${nodeClient.id.value} failed.", exception)
            nodeClient.executor.failoverDecisionOn(exception) match {
              // the circuit breaker must not open a circuit for a failure which is not a node failure
              case FailoverDecision.Stop        => Task.now(Left(exception))
              case FailoverDecision.TryNextNode => Task.raiseError(exception)
            }
          }
      }
  }

  private def performIgnoringCircuit(nodeClient: NodeClient[Req, Resp], request: Req)(
      using RequestId
  ): Task[Resp] = {
    logger.debug(s"All circuits are open. Client with ID ${nodeClient.id.value} takes the request anyway.")
    perform(nodeClient, request)
  }

  private def perform(nodeClient: NodeClient[Req, Resp], request: Req)(
      using RequestId
  ): Task[Resp] = {
    nodeClient.executor.execute(request).map { response =>
      logger.trace(s"Client with ID ${nodeClient.id.value} succeeded.")
      response
    }
  }

  private def logRejectionByOpenCircuit(nodeId: NodeId, exception: Throwable)(
      using RequestId
  ): Unit = {
    exception match {
      case _: ExecutionRejectedException =>
        logger.trace(s"Client with ID ${nodeId.value} did not take the request. Its circuit is open.")
      case _ =>
        () // a node failure is logged where it is classified
    }
  }

}

object FailoverClient {

  def create[Req, Resp](
      nodeExecutors: NonEmptyList[FailoverAwareRequestExecutor[Req, Resp]],
      clock: Clock
  ): FailoverClient[Req, Resp] = {
    given CatsClock[Task] = catsClockOf(clock)
    val nodeClients =
      nodeExecutors.zipWithIndex.map((executor, idx) => new NodeClient(NodeId(idx), executor, newCircuitBreaker))
    new FailoverClient(nodeClients, clock)
  }

  private def newCircuitBreaker(
      using CatsClock[Task]
  ): CircuitBreaker[Task] = {
    CircuitBreaker.unsafe[Task](
      maxFailures = 1,
      resetTimeout = 1.second,
      exponentialBackoffFactor = 1.5,
      // the same as in RestClient
      maxResetTimeout = 30.minutes
    )
  }

  private def catsClockOf(clock: Clock): CatsClock[Task] = new CatsClock[Task] {
    override def realTime(unit: TimeUnit): Task[Long] =
      Task.delay(unit.convert(clock.millis(), TimeUnit.MILLISECONDS))

    override def monotonic(unit: TimeUnit): Task[Long] = realTime(unit)
  }

  private final case class NodeId(value: Int) extends AnyVal

  private final class NodeClient[Req, Resp](
      val id: NodeId,
      val executor: FailoverAwareRequestExecutor[Req, Resp],
      val circuitBreaker: CircuitBreaker[Task]
  )

  private sealed trait NodesToTry[Req, Resp]

  private object NodesToTry {
    final case class WithClosedOrExpiredCircuit[Req, Resp](nodes: NonEmptyList[NodeClient[Req, Resp]])
        extends NodesToTry[Req, Resp]
    final case class OnlyWithOpenCircuits[Req, Resp](node: NodeClient[Req, Resp]) extends NodesToTry[Req, Resp]
  }

}
