/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import better.files.File
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import monix.execution.atomic.AtomicBoolean
import org.apache.logging.log4j.scala.Logging

object Boot extends RorProxyApp {

  //TODO: load from real config
  override val config: RorProxy.Config = RorProxy.Config(
    targetEsNode = "http://localhost:9200",
    proxyPort = 5000,
    esConfigFile = File(getClass.getClassLoader.getResource("elasticsearch.yml"))
  )

}

trait RorProxyApp extends IOApp
  with RorProxy
  with Logging {

  private val isStarted = AtomicBoolean(false)

  def isAppStarted: Boolean = isStarted.get()

  override def run(args: List[String]): IO[ExitCode] = {
    start
      .flatMap {
        case Right(closeHandler) =>
          val proxyApp = Resource.make(IO(closeHandler))(handler =>
            IO.suspend(handler())
          )
          proxyApp
            .use { _ =>
              isStarted.set(true)
              IO.never
            }
            .as(ExitCode.Success)
        case Left(startingFailure) =>
          val errorMessage = s"Cannot start ReadonlyREST proxy: ${startingFailure.message}"
          startingFailure.throwable match {
            case Some(ex) => logger.error(errorMessage, ex)
            case None => logger.error(errorMessage)
          }
          IO.pure(ExitCode.Error)
      }
  }
}