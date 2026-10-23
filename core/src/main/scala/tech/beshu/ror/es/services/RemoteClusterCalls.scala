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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/** Applies one failure policy to a call to a remote cluster. The call starts at once and has a timeout. When the call fails and the
  * cluster has `skip_unavailable: true`, the cluster gives no results, the same as in ES. Else the call fails with
  * [[RemoteClusterCalls.RemoteClusterLookupException]], which names the cluster.
  */
object RemoteClusterCalls extends RequestIdAwareLogging {

  val callTimeout: FiniteDuration = 10.seconds

  final class RemoteClusterLookupException(cluster: ClusterName.Full, cause: Throwable)
      extends Exception(s"Cannot get indices of remote cluster [${cluster.show}]", cause)

  def guarded[A](
      cluster: ClusterName.Full,
      skipUnavailable: => Boolean,
      emptyResult: A
  )(
      call: => Task[A]
  )(
      implicit id: RequestId
  ): Task[A] = {
    startNow(call)
      .timeoutTo(callTimeout, Task.raiseError(new TimeoutException(s"timed out after ${callTimeout.toString}")))
      .onErrorHandleWith { ex =>
        if (isSkipUnavailable(cluster, skipUnavailable)) {
          logger.warn(
            s"Remote cluster [${cluster.show}] is skipped, because it has skip_unavailable: true. Cause: ${messageOf(ex)}"
          )
          Task.now(emptyResult)
        } else {
          Task.raiseError(new RemoteClusterLookupException(cluster, ex))
        }
      }
  }

  // The call starts on the caller thread, because ES keeps the request headers in a thread-local ThreadContext.
  private def startNow[A](call: => Task[A]): Task[A] =
    Try(call) match {
      case Success(task) => task
      case Failure(ex)   => Task.raiseError(ex)
    }

  private def isSkipUnavailable(cluster: ClusterName.Full, skipUnavailable: => Boolean)(
      implicit id: RequestId
  ): Boolean = {
    Try(skipUnavailable) match {
      case Success(value) =>
        value
      case Failure(ex) =>
        logger.error(s"Cannot read skip_unavailable of remote cluster [${cluster.show}]. ROR uses false.", ex)
        false
    }
  }

  private def messageOf(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
}
