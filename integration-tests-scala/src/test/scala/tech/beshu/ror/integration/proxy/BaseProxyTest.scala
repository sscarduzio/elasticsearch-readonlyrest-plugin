package tech.beshu.ror.integration.proxy

import cats.effect.{ContextShift, IO}
import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.proxy.RorProxy
import tech.beshu.ror.utils.containers.generic.ReadonlyRestEsClusterContainer

import scala.concurrent.ExecutionContext

trait BaseProxyTest
  extends BeforeAndAfterAll
    with ForAllTestContainer {
  this: Suite =>

  var handler: Option[RorProxy.CloseHandler] = None
  protected def proxyPort = 5000
  override val container: ReadonlyRestEsClusterContainer

  protected def targetEsNode = {
    val host = container.nodesContainers.head.host
    val port = container.nodesContainers.head.esMappedPort
    s"http://$host:${port}"
    s"http://$host:${port}"
  }

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

  def createApp() = new RorProxy {
    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = targetEsNode,
      proxyPort = proxyPort.toString,
      rorConfigFile = null
    )

    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}
