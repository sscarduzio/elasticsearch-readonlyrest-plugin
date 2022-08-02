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
package tech.beshu.ror.utils.proxy

import better.files.{Resource => _, _}
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import os.SubProcess
import tech.beshu.ror.utils.containers.EsContainerWithRorSecurity.rorAdminCredentials
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.{RorPluginGradleProject, RorProxyGradleProject}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsStartupChecker

import scala.concurrent.duration._
import scala.language.postfixOps

class RorProxyInstance private(val esVersion: String, val port: Int, proxyProcess: SubProcess)
  extends LazyLogging {

  def close(): Unit = {
    proxyProcess.destroy()
  }
}

object RorProxyInstance extends LazyLogging {

  def start(proxyPort: Int,
            rorConfig: File,
            esHost: String,
            esPort: Int,
            environmentVariables: Map[String, String]): Task[RorProxyInstance] = {
    logger.info(s"Starting proxy instance listening on port: $proxyPort, target ES node: $esHost:$esPort")
    runProxyProcess(buildProxyJar(), rorConfig, proxyPort, esHost, esPort, environmentVariables)
      .map { instance =>
        waitForProxyStart(proxyPort)
        instance
      }
  }

  private def buildProxyJar() = {
    val proxyProject = new RorProxyGradleProject("proxy")
    proxyProject.assemble.getOrElse(throw new ContainerCreationException("Proxy file assembly failed")).toScala
  }

  private def runProxyProcess(proxyJar: File,
                              rorConfig: File,
                              proxyPort: Int,
                              esHost: String,
                              esPort: Int,
                              environmentVariables: Map[String, String]) = Task {
    val proc = os
      .proc("java",
        s"-Dcom.readonlyrest.settings.file.path=${rorConfig.pathAsString}",
        s"-Dcom.readonlyrest.proxy.es.host=$esHost",
        s"-Dcom.readonlyrest.proxy.es.port=$esPort",
        s"-Dcom.readonlyrest.proxy.port=$proxyPort",
        s"-Dcom.readonlyrest.settings.loading.delay=1",
        s"-Dio.netty.tryReflectionSetAccessible=true",
        s"-Xdebug", "-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n",
        s"-jar", proxyJar.pathAsString)
      .spawn(
        stdout = os.Inherit,
        env = environmentVariables
      )
    new RorProxyInstance(RorPluginGradleProject.fromSystemProperty.getESVersion, proxyPort, proc)
  }

  private def waitForProxyStart(port: Int): Unit = {
    implicit val timeout: FiniteDuration = 1 minute
    val startupChecker = EsStartupChecker.accessibleEsChecker("ROR proxy", createAdminRestClient(port))
    if (!startupChecker.waitForStart()) {
      throw new IllegalStateException(s"Cannot start proxy on port=$port")
    }
  }

  private def createAdminRestClient(port: Int) = {
    new RestClient(false, "localhost", port, Some(rorAdminCredentials._1, rorAdminCredentials._2))
  }

}
