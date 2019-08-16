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
package tech.beshu.ror.utils.containers

import java.util.concurrent.TimeUnit

import cats.Id
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.apache.http.client.methods.HttpGet
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import retry._
import retry.RetryPolicies._
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.HttpResponseHelper.deserializeJsonBody
import tech.beshu.ror.utils.misc.ScalaUtils._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class ElasticsearchNodeWaitingStrategy(esVersion: String,
                                       containerName: String,
                                       restClient: Coeval[RestClient],
                                       initializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer)
  extends AbstractWaitStrategy
  with StrictLogging {

  override def waitUntilReady(): Unit = {
    implicit val startupThreshold: FiniteDuration = FiniteDuration(startupTimeout.toMillis, TimeUnit.MILLISECONDS)
    val client = restClient.runAttempt().fold(throw _, identity)
    val started = retry {
      checkClusterHealth(client).fold(
        throwable => {
          logger.error(s"[$containerName] Cannot Check cluster health: ${throwable.getLocalizedMessage}")
          false
        },
        identity
      )
    }
    if(!started) {
      throw new ContainerLaunchException(s"Cannot start ROR-ES container [$containerName]")
    }
    initializer.initialize(esVersion, client)
  }

  private def retry[A](checkClusterHealthAction: => Boolean)
                      (implicit startupThreshold: FiniteDuration) = {
    val policy: RetryPolicy[Id] = limitRetriesByCumulativeDelay(startupThreshold, constantDelay(2 seconds))
    val predicate = (_: Boolean) == true
    def onFailure(failedValue: Boolean, details: RetryDetails): Unit = {
      logger.debug(s"[$containerName] Cluster not ready yet. Retrying ...")
    }
    retrying(policy, predicate, onFailure) {
      checkClusterHealthAction
    }
  }

  private def checkClusterHealth(client: RestClient) = {
    val clusterHealthRequest = new HttpGet(client.from("_cluster/health"))
    Try(client.execute(clusterHealthRequest)).bracket { response =>
      response.getStatusLine.getStatusCode match {
        case 200 =>
          val healthJson = deserializeJsonBody(RestClient.bodyFrom(response))
          "green" == healthJson.get("status")
        case _ =>
          false
      }
    }
  }
}

trait ElasticsearchNodeDataInitializer {
  def initialize(esVersion: String, adminRestClient: RestClient): Unit
}

object NoOpElasticsearchNodeDataInitializer extends ElasticsearchNodeDataInitializer {
  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {}
}