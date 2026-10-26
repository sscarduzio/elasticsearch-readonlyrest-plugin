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

import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import org.apache.logging.log4j.Level
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.RemoteClusterCalls
import tech.beshu.ror.utils.LogEventsCapture.captureLogEvents

import java.io.IOException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.Success

class RemoteClusterCallsTests extends AnyWordSpec with Matchers with Inside {

  private given RequestId = RequestId("test-request-id")

  private val cluster = ClusterName.Full.fromString("remote_a").get

  "RemoteClusterCalls.guarded" should {
    "return the result of a call that succeeds" in {
      val result = run(RemoteClusterCalls.guarded(cluster, false, List.empty[String])(Task.now(List("index1"))))

      result should be(Success(Right(List("index1"))))
    }
    "return the empty result when the call fails and skip_unavailable is true" in {
      val result = run(RemoteClusterCalls.guarded(cluster, true, List.empty[String])(failingCall))

      result should be(Success(Right(List.empty)))
    }
    "return an error that names the cluster when the call fails and skip_unavailable is false" in {
      val result = run(RemoteClusterCalls.guarded(cluster, false, List.empty[String])(failingCall))

      inside(result) { case Success(Left(error)) =>
        error.cluster should be(cluster)
        error.message should be("Cannot get indices of remote cluster [remote_a]: connection refused")
        error.cause shouldBe an[IOException]
        error.cause.getMessage should be("connection refused")
      }
    }
    "apply the same policy when the call throws before it returns a Task" in {
      def throwingCall: Task[List[String]] = throw new IllegalStateException("no such remote cluster")

      run(RemoteClusterCalls.guarded(cluster, true, List.empty[String])(throwingCall)) should be(
        Success(Right(List.empty))
      )
      inside(run(RemoteClusterCalls.guarded(cluster, false, List.empty[String])(throwingCall))) {
        case Success(Left(error)) =>
          error.message should be("Cannot get indices of remote cluster [remote_a]: no such remote cluster")
          error.cause.getMessage should be("no such remote cluster")
      }
    }
    "apply the same policy when the call times out" in {
      inside(runWithTimeout(skipUnavailable = false)) { case Some(Success(Left(error))) =>
        error.message should be("Cannot get indices of remote cluster [remote_a]: timed out after 10 seconds")
        error.cause shouldBe a[TimeoutException]
        error.cause.getMessage should be("timed out after 10 seconds")
      }
      runWithTimeout(skipUnavailable = true) should be(Some(Success(Right(List.empty))))
    }
    "treat skip_unavailable as false when it cannot be read" in {
      def skipUnavailable: Boolean = throw new IllegalStateException("cannot read the setting")

      val result = run(RemoteClusterCalls.guarded(cluster, skipUnavailable, List.empty[String])(failingCall))

      inside(result) { case Success(Left(error)) =>
        error.cause.getMessage should be("connection refused")
      }
    }
    "start the call when the Task runs" in {
      var started = false
      def call: Task[List[String]] = { started = true; Task.now(List("index1")) }

      val task = RemoteClusterCalls.guarded(cluster, false, List.empty[String])(call)

      started should be(false)
      run(task) should be(Success(Right(List("index1"))))
      started should be(true)
    }
    "log a skip at WARN only when the cluster becomes unavailable" in {
      // Other tests use other clusters, so this cluster is not skipped before the test
      val flappingCluster = ClusterName.Full.fromString("remote_flapping").get
      def call(task: Task[List[String]]) =
        run(RemoteClusterCalls.guarded(flappingCluster, true, List.empty[String])(task))

      val events = captureLogEvents(RemoteClusterCalls.getClass.getName) {
        call(failingCall)
        call(failingCall)
        call(Task.now(List("index1")))
        call(failingCall)
      }

      events.filter(_.message.contains("[remote_flapping]")).map(_.level) should be(
        List(Level.WARN, Level.DEBUG, Level.INFO, Level.WARN)
      )
    }
    "not read skip_unavailable when the call succeeds" in {
      def skipUnavailable: Boolean = fail("skip_unavailable must not be read")

      val result =
        run(RemoteClusterCalls.guarded(cluster, skipUnavailable, List.empty[String])(Task.now(List("index1"))))

      result should be(Success(Right(List("index1"))))
    }
  }

  private def failingCall: Task[List[String]] = Task.raiseError(new IOException("connection refused"))

  private def run[A](task: Task[A]) = {
    val scheduler = TestScheduler()
    val future = task.runToFuture(scheduler)
    scheduler.tick()
    future.value.get
  }

  private def runWithTimeout(skipUnavailable: Boolean) = {
    val scheduler = TestScheduler()
    val future = RemoteClusterCalls
      .guarded(cluster, skipUnavailable, List.empty[String])(Task.never[List[String]])
      .runToFuture(scheduler)
    scheduler.tick(RemoteClusterCalls.callTimeout - 1.millisecond)
    future.value should be(None)
    scheduler.tick(1.millisecond)
    future.value
  }

}
