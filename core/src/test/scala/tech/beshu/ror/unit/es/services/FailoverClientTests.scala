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
package tech.beshu.ror.unit.es.services

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.MultiNodeRestClient.{FailoverDecision, RequestExecutor}
import tech.beshu.ror.es.services.{DelegatingMultiNodeRestClient, FailoverClient, MultiNodeRestClient}

import java.io.IOException
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.*

class FailoverClientTests extends AnyWordSpec with Matchers {

  private given RequestId = RequestId("test-request-id")

  "FailoverClient" should {
    "deliver the response from the first node when it succeeds" in {
      val node1 = new RecordingExecutor(_ => Right("node1-response"))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      val result = performRequest(client)

      result should be(Right("node1-response"))
      node1.receivedRequests should have size 1
      node2.receivedRequests should have size 0
    }

    "try the next node when a failure is classified as retryable" in {
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      val result = performRequest(client)

      result should be(Right("node2-response"))
      node1.receivedRequests should have size 1
      node2.receivedRequests should have size 1
    }

    "fail without trying other nodes when a failure is classified as fatal" in {
      val fatalException = new IllegalStateException("bad request")
      val node1 = new RecordingExecutor(_ => Left(fatalException))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      val result = performRequest(client)

      result should be(Left(fatalException))
      node2.receivedRequests should have size 0
    }

    "fail with the last exception when all nodes fail with retryable failures" in {
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val lastException = new IOException("node2 down")
      val node2 = new RecordingExecutor(_ => Left(lastException))
      val client = failoverClient(new TestClock, node1, node2)

      val result = performRequest(client)

      result should be(Left(lastException))
    }

    "not open a circuit for a node which failed with a fatal failure" in {
      val node1 = new RecordingExecutor({
        case 1 => Left(new IllegalStateException("bad request"))
        case _ => Right("node1-response")
      })
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      performRequest(client)
      val result = performRequest(client)

      // circuit stays closed - node1 is preferred again on the next request instead of being skipped in favour of node2
      node1.receivedRequests should have size 2
      node2.receivedRequests should have size 0
      result should be(Right("node1-response"))
    }

    "open a circuit for a node which failed with a retryable failure and skip it while the circuit is open" in {
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      performRequest(client)
      val result = performRequest(client)

      result should be(Right("node2-response"))
      node1.receivedRequests should have size 1
      node2.receivedRequests should have size 2
    }

    "allow a trial request one second after the first failure" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor({
        case 1 => Left(new IOException("node1 down"))
        case _ => Right("node1-response")
      })
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(clock, node1, node2)

      performRequest(client)
      clock.advance(999.millis)
      performRequest(client)
      node1.receivedRequests should have size 1 // circuit still open

      clock.advance(1.milli)
      val result = performRequest(client)
      node1.receivedRequests should have size 2 // trial request allowed
      result should be(Right("node1-response"))
    }

    "close the circuit after a successful trial request" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor({
        case 1 => Left(new IOException("node1 down"))
        case _ => Right("node1-response")
      })
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(clock, node1, node2)

      performRequest(client)
      clock.advance(1.second)
      performRequest(client)
      val result = performRequest(client) // no clock advance needed - circuit is closed again

      node1.receivedRequests should have size 3
      result should be(Right("node1-response"))
    }

    "keep the circuit open longer after each consecutive trial failure" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(clock, node1, node2)

      performRequest(client) // node1 fails, circuit open for 1s
      node1.receivedRequests should have size 1

      clock.advance(1.second)
      performRequest(client) // trial fails, circuit open for 1.5s
      node1.receivedRequests should have size 2

      clock.advance(1499.millis)
      performRequest(client)
      node1.receivedRequests should have size 2 // circuit still open

      clock.advance(1.milli)
      performRequest(client) // trial fails, circuit open for 2.25s
      node1.receivedRequests should have size 3
    }

    "cap the open circuit duration at 30 minutes" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(clock, node1, node2)

      (1 to 20).foreach { _ =>
        performRequest(client)
        clock.advance(30.minutes)
      }
      node1.receivedRequests should have size 20 // without the cap the node would stop being tried

      performRequest(client)
      node1.receivedRequests should have size 21
      clock.advance(30.minutes - 1.milli)
      performRequest(client)
      node1.receivedRequests should have size 21 // circuit still open

      clock.advance(1.milli)
      performRequest(client)
      node1.receivedRequests should have size 22
    }

    "try the node whose circuit allows a trial request soonest when all circuits are open" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor({
        case 2 => Left(new IOException("node2 down"))
        case _ => Right("node2-response")
      })
      val client = failoverClient(clock, node1, node2)

      performRequest(client) // node1 fails (circuit open until 1s), node2 succeeds
      clock.advance(1.second)
      performRequest(client) // trial on node1 fails (circuit open until 2.5s), then node2 fails (circuit open until 2s)

      node1.receivedRequests should have size 2
      node2.receivedRequests should have size 2

      clock.advance(100.millis)
      val result = performRequest(client) // all circuits open - node2 allows a trial soonest (2s < 2.5s)

      node1.receivedRequests should have size 2
      node2.receivedRequests should have size 3
      result should be(Right("node2-response"))
    }

    "fail the request without trying other nodes when all circuits are open and the soonest-trial node fails" in {
      val clock = new TestClock
      val node1 = new RecordingExecutor(_ => Left(new IOException("node1 down")))
      val node2 = new RecordingExecutor({
        case 1 => Right("node2-response")
        case _ => Left(new IOException("node2 down"))
      })
      val client = failoverClient(clock, node1, node2)

      performRequest(client) // node1 fails (circuit open until 1s), node2 succeeds
      clock.advance(1.second)
      performRequest(client) // trial on node1 fails (circuit open until 2.5s), then node2 fails (circuit open until 2s)

      clock.advance(100.millis)
      val result = performRequest(client) // all circuits open: only node2 (soonest trial) is tried

      node1.receivedRequests should have size 2 // node1 not tried even though node2 failed
      node2.receivedRequests should have size 3
      result.isLeft should be(true)
    }

    "close all node clients on close" in {
      val node1 = new RecordingExecutor(_ => Right("node1-response"))
      val node2 = new RecordingExecutor(_ => Right("node2-response"))
      val client = failoverClient(new TestClock, node1, node2)

      client.close()

      node1.closed should be(true)
      node2.closed should be(true)
    }
  }

  "DelegatingMultiNodeRestClient" should {
    "delegate requests to the underlying executor" in {
      val executor = new RecordingExecutor(_ => Right("response"))
      val client = new DelegatingMultiNodeRestClient(executor)

      val result = performRequest(client)

      result should be(Right("response"))
      executor.receivedRequests.toList should be(List("request"))
    }

    "close the underlying executor" in {
      val executor = new RecordingExecutor(_ => Right("response"))
      val client = new DelegatingMultiNodeRestClient(executor)

      client.close()

      executor.closed should be(true)
    }
  }

  private def failoverClient(clock: Clock, executors: RecordingExecutor*) = {
    FailoverClient.create[String, String](
      nodeExecutors = NonEmptyList.fromListUnsafe(executors.toList),
      failoverDecision = failoverDecision,
      clock = clock
    )
  }

  private val failoverDecision: Throwable => FailoverDecision = {
    case _: IOException => FailoverDecision.TryNextNode
    case _              => FailoverDecision.Stop
  }

  private def performRequest(client: MultiNodeRestClient[String, String]) = {
    client.perform("request").attempt.runSyncUnsafe()
  }

  // responds based on the number of requests received so far (1-based)
  private class RecordingExecutor(respond: Int => Either[Exception, String]) extends RequestExecutor[String, String] {
    val receivedRequests: scala.collection.mutable.ListBuffer[String] = scala.collection.mutable.ListBuffer.empty
    var closed: Boolean = false

    override def execute(request: String): Task[String] = Task.defer {
      receivedRequests += request
      respond(receivedRequests.size) match {
        case Right(response) => Task.now(response)
        case Left(exception) => Task.raiseError(exception)
      }
    }

    override def close(): Unit = closed = true
  }

  private final class TestClock extends Clock {
    private var current: Instant = Instant.EPOCH

    def advance(duration: FiniteDuration): Unit = {
      current = current.plusMillis(duration.toMillis)
    }

    override def instant(): Instant = current

    override def getZone: ZoneId = ZoneOffset.UTC

    override def withZone(zone: ZoneId): Clock = this
  }

}
