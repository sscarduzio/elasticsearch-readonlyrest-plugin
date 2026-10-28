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
import monix.execution.atomic.Atomic
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.EsClusterService.{RemoteClusterLookupError, messageOf}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/** Applies one failure policy to a call to a remote cluster. The call starts when the Task runs and has a timeout. When
  * the call fails and the cluster has `skip_unavailable: true`, the cluster gives no results, the same as in ES. Else the
  * result is a [[EsClusterService.RemoteClusterLookupError]], which names the cluster and the cause.
  *
  * The first failure of a cluster is logged at WARN and the next failures at DEBUG. A change of `skip_unavailable` during
  * the failures gives one more WARN line, because the result of the requests changes. The first successful call after
  * the failures is logged at INFO. Thus one outage of a cluster gives one WARN line.
  */
object RemoteClusterCalls extends RequestIdAwareLogging {

  val callTimeout: FiniteDuration = 10.seconds

  // The value of skip_unavailable at the last failure of each unavailable cluster
  private val unavailableClusters = Atomic(Map.empty[ClusterName.Full, Boolean])

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
            logUnavailable(
              cluster,
              skipUnavailable = true,
              s"Remote cluster [${cluster.show}] is skipped, because it has skip_unavailable: true. Cause: ${messageOf(ex)}"
            )
            Right(emptyResult)
          } else {
            logUnavailable(
              cluster,
              skipUnavailable = false,
              s"Remote cluster [${cluster.show}] is unavailable, and it has skip_unavailable: false. " +
                s"The searches that name it fail. Cause: ${messageOf(ex)}"
            )
            Left(RemoteClusterLookupError(cluster, ex))
          },
        result => {
          if (markAvailable(cluster)) logger.info(s"Remote cluster [${cluster.show}] is available again")
          Right(result)
        }
      )
  }

  private def logUnavailable(cluster: ClusterName.Full, skipUnavailable: Boolean, message: String)(
      implicit id: RequestId
  ): Unit =
    if (markUnavailable(cluster, skipUnavailable)) logger.warn(message)
    else logger.debug(message)

  // True when the cluster was available before, or had the other value of skip_unavailable
  private def markUnavailable(cluster: ClusterName.Full, skipUnavailable: Boolean): Boolean =
    unavailableClusters.transformAndExtract { clusters =>
      (!clusters.get(cluster).contains(skipUnavailable), clusters.updated(cluster, skipUnavailable))
    }

  // True when the cluster was unavailable before
  private def markAvailable(cluster: ClusterName.Full): Boolean =
    unavailableClusters.transformAndExtract(clusters => (clusters.contains(cluster), clusters - cluster))

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
