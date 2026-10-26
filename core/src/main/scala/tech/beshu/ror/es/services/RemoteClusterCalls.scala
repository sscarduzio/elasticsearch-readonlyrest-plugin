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
import tech.beshu.ror.es.services.EsClusterService.{RemoteClusterLookupError, messageOf}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.{ConcurrentHashMap, TimeoutException}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/** Applies one failure policy to a call to a remote cluster. The call starts when the Task runs and has a timeout. When
  * the call fails and the cluster has `skip_unavailable: true`, the cluster gives no results, the same as in ES. Else the
  * result is a [[EsClusterService.RemoteClusterLookupError]], which names the cluster and the cause.
  */
object RemoteClusterCalls extends RequestIdAwareLogging {

  val callTimeout: FiniteDuration = 10.seconds

  // While a skipped cluster is down, each request skips it. Only the first skip in this interval is logged at WARN.
  val skipWarningInterval: FiniteDuration = 1.minute

  private val lastSkipWarningNanos = new ConcurrentHashMap[ClusterName.Full, java.lang.Long]()

  def guarded[A](
      cluster: ClusterName.Full,
      skipUnavailable: => Boolean,
      emptyResult: A
  )(
      call: => Task[A]
  )(
      implicit id: RequestId
  ): Task[Either[RemoteClusterLookupError, A]] = {
    Task
      .defer(call)
      .timeoutTo(callTimeout, Task.raiseError(new TimeoutException(s"timed out after ${callTimeout.toString}")))
      .redeem(
        ex =>
          if (isSkipUnavailable(cluster, skipUnavailable)) {
            logSkipped(cluster, ex)
            Right(emptyResult)
          } else {
            Left(RemoteClusterLookupError(cluster, ex))
          },
        Right(_)
      )
  }

  private def logSkipped(cluster: ClusterName.Full, ex: Throwable)(
      implicit id: RequestId
  ): Unit = {
    val message =
      s"Remote cluster [${cluster.show}] is skipped, because it has skip_unavailable: true. Cause: ${messageOf(ex)}"
    if (isFirstSkipInInterval(cluster)) logger.warn(message)
    else logger.debug(message)
  }

  private def isFirstSkipInInterval(cluster: ClusterName.Full): Boolean = {
    val now = System.nanoTime()
    var first = false
    lastSkipWarningNanos.compute(
      cluster,
      (_, last) =>
        if (last == null || now - last >= skipWarningInterval.toNanos) {
          first = true
          now
        } else {
          last
        }
    )
    first
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

}
