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
package tech.beshu.ror.utils.misc

import cats.effect.Resource
import cats.implicits.*
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.httpclient.HttpResponseHelper.deserializeJsonBody
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsStartupChecker.{ClusterNotReady, Mode}

import scala.concurrent.duration.*
import scala.language.postfixOps

class EsStartupChecker private(name: String,
                               client: RestClient,
                               mode: Mode)
  extends LazyLogging {

  def waitForStart(): Boolean = {
    retryBackoff(clusterIsReady(client), maxRetries = 150, interval = 2 seconds)
      .map((_: Unit) => true)
      .onErrorRecover(_ => false)
      .runSyncUnsafe(5 minutes)
  }

  private def retryBackoff[A](source: Task[A],
                              maxRetries: Int,
                              interval: FiniteDuration): Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          retryBackoff(source, maxRetries - 1, interval).delayExecution(interval)
        else
          Task.raiseError(ex)
    }
  }

  private def clusterIsReady(client: RestClient): Task[Unit] = {
    Resource
      .make(
        Task
          .delay(client.execute(new HttpGet(client.from("_cluster/health"))))
          .recoverWith { ex =>
            logger.error(s"[$name] ES not ready yet, healthcheck failed")
            Task.raiseError(ex)
          }
      )(
        response => Task.delay(response.close())
      )
      .use { response =>
        response.getStatusLine.getStatusCode match {
          case 200 =>
            mode match {
              case Mode.GreenCluster =>
                val healthJson = deserializeJsonBody(RestClient.bodyFrom(response))
                val healthStatus = healthJson.get("status")
                if (healthStatus == "green") {
                  logger.info(s"[$name] ES is ready")
                  Task.unit
                } else {
                  logger.info(s"[$name] ES not ready yet, health status is $healthStatus")
                  Task.raiseError(ClusterNotReady)
                }
              case Mode.Accessible =>
                logger.info(s"[$name] ES is ready")
                Task.unit
            }
          case 401 =>
            logger.warn(s"[$name] ES ready, but with status 401 (ROR probably not installed)")
            Task.unit
          case otherStatus =>
            logger.info(s"[$name] ES not ready yet, received HTTP $otherStatus")
            Task.raiseError(ClusterNotReady)
        }
      }
  }
}

object EsStartupChecker {

  private case object ClusterNotReady extends Exception

  def greenEsClusterChecker(name: String, client: RestClient): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.GreenCluster)

  def accessibleEsChecker(name: String, client: RestClient): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.Accessible)

  private sealed trait Mode

  private object Mode {
    case object GreenCluster extends Mode

    case object Accessible extends Mode
  }
}
