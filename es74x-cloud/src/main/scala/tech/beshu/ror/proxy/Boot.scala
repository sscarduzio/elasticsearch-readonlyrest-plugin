/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import org.apache.logging.log4j.scala.Logging

object Boot
  extends IOApp
    with RorProxyApp
    with Logging {

  override val config: RorProxy.Config = RorProxy.Config(
    targetEsNode = "http://localhost:9200",
    proxyPort = "5000",
    esConfigFile = None
  )

  override def run(args: List[String]): IO[ExitCode] = {
    start
      .flatMap {
        case Right(closeHandler) =>
          val proxyApp = Resource.make(IO(closeHandler))(handler =>
            IO.suspend(handler())
          )
          proxyApp.use(_ => IO.never).as(ExitCode.Success)
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
