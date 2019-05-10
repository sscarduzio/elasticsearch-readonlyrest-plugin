package tech.beshu.ror.integration.utils.containers

import java.util.concurrent.TimeUnit

import cats.Id
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.apache.http.client.methods.HttpGet
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import retry.RetryPolicies.{constantDelay, limitRetriesByCumulativeDelay}
import retry.{RetryDetails, RetryPolicy, retrying}
import tech.beshu.ror.integration.utils.JavaScalaUtils.bracket
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class ElasticsearchNodeWaitingStrategy(containerName: String,
                                       restClient: Coeval[RestClient],
                                       initializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer)
  extends AbstractWaitStrategy
  with StrictLogging {

  private val startupThreshold = FiniteDuration(startupTimeout.toMillis, TimeUnit.MILLISECONDS)

  override def waitUntilReady(): Unit = {
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
    initializer.initialize(new DocumentManager(client))
  }

  private def retry[A](checkClusterHealthAction: => Boolean) = {
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
    bracket(Try(client.execute(clusterHealthRequest))) { response =>
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
  def initialize(documentManager: DocumentManager): Unit
}

object NoOpElasticsearchNodeDataInitializer extends ElasticsearchNodeDataInitializer {
  override def initialize(documentManager: DocumentManager): Unit = {}
}