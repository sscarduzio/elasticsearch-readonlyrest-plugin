package tech.beshu.ror.utils.misc

import cats.Id
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.apache.http.client.methods.HttpGet
import retry.RetryPolicies.{constantDelay, limitRetriesByCumulativeDelay}
import retry.{RetryDetails, RetryPolicy, retrying}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsStartupChecker.Mode
import tech.beshu.ror.utils.misc.HttpResponseHelper.deserializeJsonBody
import tech.beshu.ror.utils.misc.ScalaUtils._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class EsStartupChecker private(name: String,
                               client: RestClient,
                               mode: Mode)
                              (implicit val startupTimeout: FiniteDuration)
  extends LazyLogging {

  def waitForStart(): Boolean = {
    retry {
      checkClusterHealth(client).fold(
        throwable => {
          logger.error(s"[$name] Cannot check ES health: ${throwable.getLocalizedMessage}")
          false
        },
        identity
      )
    }
  }

  private def retry[A](checkClusterHealthAction: => Boolean)
                      (implicit startupThreshold: FiniteDuration) = {
    val policy: RetryPolicy[Id] = limitRetriesByCumulativeDelay(startupThreshold, constantDelay(2 seconds))
    val predicate = (_: Boolean) == true

    def onFailure(failedValue: Boolean, details: RetryDetails): Unit = {
      logger.debug(s"[$name] ES not ready yet. Retrying ...")
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
          mode match {
            case Mode.GreenCluster =>
              val healthJson = deserializeJsonBody(RestClient.bodyFrom(response))
              "green" == healthJson.get("status")
            case Mode.Accessible =>
              true
          }
        case _ =>
          false
      }
    }
  }
}

object EsStartupChecker {

  def greenEsClusterChecker(name: String, client: RestClient)
                           (implicit startupTimeout: FiniteDuration): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.GreenCluster)

  def accessibleEsChecker(name: String, client: RestClient)
                         (implicit startupTimeout: FiniteDuration): EsStartupChecker =
    new EsStartupChecker(name, client, Mode.Accessible)

  private sealed trait Mode
  private object Mode {
    case object GreenCluster extends Mode
    case object Accessible extends Mode
  }
}
