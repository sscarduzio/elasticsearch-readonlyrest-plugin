package tech.beshu.ror.integration.proxy

import cats.effect.{ContextShift, IO}
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.proxy.RorProxy

import scala.concurrent.ExecutionContext

trait BaseProxyTest extends BeforeAndAfterAll {
  this: Suite =>

  private val app = new RorProxy {
    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }

  val proxyCloseHandler = app.start.unsafeRunSync().getOrElse(throw new Exception("Could not start proxy"))

  override protected def afterAll(): Unit = {
    proxyCloseHandler().unsafeRunSync()
  }
}
