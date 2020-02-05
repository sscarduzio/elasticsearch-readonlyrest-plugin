package tech.beshu.ror.integration.proxy

import cats.effect.{ContextShift, IO}
import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.proxy.RorProxy
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.generic.{CallingProxy, EsClusterProvider, TargetEsContainer}

import scala.concurrent.ExecutionContext

trait ProxyTestSupport
  extends BeforeAndAfterAll
    with ForAllTestContainer
    with CallingProxy
    with EsClusterProvider {
  this: Suite with TargetEsContainer =>

  protected def rorConfigFileName: String

  private var handler: Option[RorProxy.CloseHandler] = None
  override val proxyPort = 5000

  override def afterStart(): Unit = {
    super.afterStart()
    handler = createApp()
      .start
      .unsafeRunSync()
      .toOption
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    handler.getOrElse(throw new Exception("Could not start test proxy instance"))().unsafeRunSync()
  }

  private def createApp() = new RorProxy {

    System.setProperty("com.readonlyrest.settings.file.path", ContainerUtils.getResourceFile(rorConfigFileName).getAbsolutePath)

    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = s"http://${targetEsContainer.host}:${targetEsContainer.port}",
      proxyPort = proxyPort.toString,
      esConfigFile = None
    )

    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}
