package tech.beshu.ror.utils.proxy

import java.util.Optional

import better.files.{Resource => _, _}
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import os.SubProcess
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.containers.providers.ClientProvider.adminCredentials
import tech.beshu.ror.utils.gradle.RorProxyGradleProject
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{EsStartupChecker, Tuple}

import scala.concurrent.duration._
import scala.language.postfixOps

class RorProxyInstance private(val port: Int, proxyProcess: SubProcess)
  extends LazyLogging {

  def close(): Unit = {
    proxyProcess.destroy()
  }
}

object RorProxyInstance extends LazyLogging {

  def start(proxyPort: Int, rorConfig: File, esHost: String, esPort: Int): Task[RorProxyInstance] = {
    logger.info(s"Starting proxy instance listening on port: $proxyPort, target ES node: $esHost:$esPort")
    runProxyProcess(buildProxyJar(), rorConfig, proxyPort, esHost, esPort)
      .map { instance =>
        waitForProxyStart(proxyPort)
        instance
      }
  }

  private def buildProxyJar() = {
    val proxyProject = new RorProxyGradleProject("es74x-cloud")
    proxyProject.assemble.getOrElse(throw new ContainerCreationException("Proxy file assembly failed")).toScala
  }

  private def runProxyProcess(proxyJar: File,
                              rorConfig: File,
                              proxyPort: Int,
                              esHost: String,
                              esPort: Int) = Task {
    val proc = os
      .proc("java",
        s"-Dcom.readonlyrest.settings.file.path=${rorConfig.pathAsString}",
        s"-Dcom.readonlyrest.proxy.targetEsAddress=$esHost:$esPort",
        s"-Dcom.readonlyrest.proxy.port=$proxyPort",
        s"-Dio.netty.tryReflectionSetAccessible=true",
        s"-Xdebug", "-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n",
        s"-jar", proxyJar.pathAsString)
      .spawn(stdout = os.Inherit)
    new RorProxyInstance(proxyPort, proc)
  }

  private def waitForProxyStart(port: Int): Unit = {
    implicit val timeout: FiniteDuration = 1 minute
    val startupChecker = EsStartupChecker.accessibleEsChecker("ROR proxy", createAdminRestClient(port))
    if (!startupChecker.waitForStart()) {
      throw new IllegalStateException(s"Cannot start proxy on port=$port")
    }
  }

  private def createAdminRestClient(port: Int) = {
    new RestClient(false, "localhost", port, Optional.of(Tuple.from(adminCredentials._1, adminCredentials._2)))
  }

}
