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

import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.{AbstractWaitStrategy, HttpWaitStrategy}
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs
import tech.beshu.ror.utils.misc.{EsStartupChecker, OsUtils, Version}

import scala.language.postfixOps
import scala.util.Try

class ElasticsearchNodeWaitingStrategy(esVersion: String,
                                       esPort: Int,
                                       containerName: String,
                                       restClient: Coeval[RestClient],
                                       initializer: ElasticsearchNodeDataInitializer,
                                       strategy: AwaitingReadyStrategy)
  extends AbstractWaitStrategy
    with StrictLogging {

  override def waitUntilReady(): Unit = {
    val client = createRestClient()
    if (!waitForStart(client)) {
      throw new ContainerLaunchException(s"Cannot start ROR-ES container [$containerName]")
    }
    initialize(client)
  }

  private def waitForStart(client: RestClient) = {
    strategy match {
      case AwaitingReadyStrategy.WaitForEsReadiness =>
        createWaitForReadinessChecker(client).waitForStart()
      case AwaitingReadyStrategy.ImmediatelyTreatAsReady =>
        true
      case AwaitingReadyStrategy.WaitForEsRestApiResponsive =>
        waitForRestEsApi(client)
    }
  }

  private def initialize(client: RestClient): Unit = {
    Try(initializer.initialize(esVersion, client))
      .fold(
        ex => throw new ContainerLaunchException(s"Cannot start ROR-ES container [$containerName]", ex),
        identity
      )
  }

  private def createRestClient() = {
    restClient.runAttempt().fold(throw _, identity)
  }

  private def createWaitForReadinessChecker(client: RestClient) = {
    if (Version.greaterOrEqualThan(esVersion, 8, 3, 0)) {
      EsStartupChecker.greenEsClusterChecker(containerName, client)
    } else {
      EsStartupChecker.accessibleEsChecker(containerName, client)
    }
  }

  private def waitForRestEsApi(client: RestClient) = {
    OsUtils.currentOs match {
      case CurrentOs.Windows =>
        EsStartupChecker
          .reachableEsChecker(containerName, client)
          .waitForStart()
      case CurrentOs.OtherThanWindows =>
        val esRestApiWaitStrategy = new HttpWaitStrategy()
          .usingTls().allowInsecure()
          .forPort(esPort)
          .forPath("/")
          .forStatusCodeMatching(_ => true)
        Try(esRestApiWaitStrategy.waitUntilReady(waitStrategyTarget)).isSuccess
    }
  }
}

object ElasticsearchNodeWaitingStrategy {
  sealed trait AwaitingReadyStrategy

  object AwaitingReadyStrategy {
    case object WaitForEsReadiness extends AwaitingReadyStrategy
    case object ImmediatelyTreatAsReady extends AwaitingReadyStrategy
    case object WaitForEsRestApiResponsive extends AwaitingReadyStrategy
  }
}
