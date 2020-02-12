package tech.beshu.ror.integration.proxy

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

  private def createApp(): RorProxy = new RorProxyApp {

    System.setProperty("com.readonlyrest.settings.file.path", ContainerUtils.getResourceFile(rorConfigFileName).getAbsolutePath)

    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = s"http://${targetEsContainer.host}:${targetEsContainer.port}",
      proxyPort = proxyPort.toString,
      esConfigFile = None
    )

    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}
