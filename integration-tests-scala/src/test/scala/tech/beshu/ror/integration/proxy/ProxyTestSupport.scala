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
package tech.beshu.ror.integration.proxy

import better.files._
import cats.effect.{ContextShift, IO}
import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.proxy.{RorProxy, RorProxyApp}
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.generic.{CallingProxy, EsWithoutRorPluginContainerCreator, TargetEsContainer}

import scala.concurrent.ExecutionContext

trait ProxyTestSupport
  extends BeforeAndAfterAll
    with ForAllTestContainer
    with CallingProxy
    with EsWithoutRorPluginContainerCreator {
  this: Suite with TargetEsContainer =>

  def rorConfigFileName: String

  private var closeHandler: Option[RorProxy.CloseHandler] = None
  override val proxyPort = 5000

  override def afterStart(): Unit = {
    super.afterStart()
    closeHandler = createApp()
      .start
      .unsafeRunSync()
      .toOption
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    closeHandler.getOrElse(throw new Exception("Could not start test proxy instance"))().unsafeRunSync()
  }

  //TODO: Create proxy instance dynamically based on 'esModule' property. Now it's fixed on es74-cloud
  private def createApp(): RorProxy = new RorProxyApp {

    System.setProperty("com.readonlyrest.settings.file.path", ContainerUtils.getResourceFile(rorConfigFileName).getAbsolutePath)

    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = s"http://${targetEsContainer.host}:${targetEsContainer.port}",
      proxyPort = proxyPort.toString,
      esConfigFile = ContainerUtils.getResourceFile("/proxy/elasticsearch.yml").toScala
    )

    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}
