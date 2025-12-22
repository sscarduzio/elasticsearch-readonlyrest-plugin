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

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.httpclient.HttpResponseHelper.deserializeJsonBody
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsStartupChecker.{ClusterNotReady, Mode}
import tech.beshu.ror.utils.misc.ScalaUtils.retryBackoff

import scala.concurrent.duration.*
import scala.language.postfixOps

class EsStartupChecker private(name: String,
                               client: RestClient,
                               mode: Mode)
  extends LazyLogging {

  def waitForStart(): Boolean = {
    retryBackoff(clusterIsReady(client), maxRetries = 150, firstDelay = 2 seconds, backOffScaler = 1)
      .map((_: Unit) => true)
      .onErrorRecover(_ => false)
      .runSyncUnsafe(5 minutes)
  }

  private def clusterIsReady(client: RestClient): Task[Unit] = {
    client
      .executeAsync(new HttpGet(client.from("_cluster/health")))
      .use { response =>
        val isOk = mode match {
          case Mode.GreenCluster => isClusterGreen(response)
          case Mode.Accessible => isClusterAccessible(response)
          case Mode.Reachable => isClusterReachable(response)
        }
        if(isOk) Task.unit
        else Task.raiseError(ClusterNotReady)
      }
  }

  private def isClusterGreen(response: HttpResponse) = {
    response.getStatusLine.getStatusCode match {
      case 200 =>
        val healthJson = deserializeJsonBody(RestClient.bodyFrom(response))
        healthJson.get("status") match {
          case "green" =>
            logger.info(s"[$name] ES is ready")
            true
          case healthStatus =>
            logger.info(s"[$name] ES not ready yet, health status is $healthStatus")
            false
        }
      case otherStatus =>
        logger.info(s"[$name] ES not ready yet, received HTTP $otherStatus")
        false
    }
  }

  private def isClusterAccessible(response: HttpResponse) = {
    response.getStatusLine.getStatusCode match {
      case 200 =>
        logger.info(s"[$name] ES is ready")
        true
      case otherStatus =>
        logger.info(s"[$name] ES not ready yet, received HTTP $otherStatus")
        false
    }
  }

  private def isClusterReachable(response: HttpResponse) = {
    logger.info(s"[$name] ES is reachable")
    true
  }
}

object EsStartupChecker {

  private case object ClusterNotReady extends Exception

  def greenEsClusterChecker(name: String, client: RestClient): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.GreenCluster)

  def accessibleEsChecker(name: String, client: RestClient): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.Accessible)

  def reachableEsChecker(name: String, client: RestClient): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.Reachable)

  private sealed trait Mode

  private object Mode {
    case object GreenCluster extends Mode
    case object Accessible extends Mode
    case object Reachable extends Mode
  }
}
