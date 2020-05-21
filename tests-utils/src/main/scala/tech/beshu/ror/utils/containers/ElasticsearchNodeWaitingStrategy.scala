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

import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsStartupChecker

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
    val checker = EsStartupChecker.greenEsClusterChecker(containerName, client)
    val started = checker.waitForStart()
    if (!started) {
      throw new ContainerLaunchException(s"Cannot start ROR-ES container [$containerName]")
    }
    Try(initializer.initialize(esVersion, client))
      .fold(
        ex => throw new ContainerLaunchException(s"Cannot start ROR-ES container [$containerName]", ex),
        identity
      )
  }
}