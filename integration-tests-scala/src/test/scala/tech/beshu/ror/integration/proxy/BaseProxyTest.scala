package tech.beshu.ror.integration.proxy

import cats.effect.{ContextShift, IO}
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.proxy.RorProxy

import scala.concurrent.ExecutionContext

trait BaseProxyTest extends BeforeAndAfterAll {
  this: Suite =>

  private val proxyCloseHandler = createApp()
    .start
    .unsafeRunSync()
    .getOrElse(throw new Exception("Could not start test proxy instance"))

  override protected def afterAll(): Unit = {
    proxyCloseHandler().unsafeRunSync()
  }

  def createApp() = new RorProxy {
    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}
