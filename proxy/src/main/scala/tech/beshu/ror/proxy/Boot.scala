/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import java.io.{BufferedReader, InputStreamReader}

import better.files.File
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import tech.beshu.ror.accesscontrol.show.logs._
import org.apache.logging.log4j.core.util.IOUtils
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.Credentials
import tech.beshu.ror.buildinfo.LogProxyBuildInfoMessage
import tech.beshu.ror.configuration.RorProperties
import tech.beshu.ror.providers.{JvmPropertiesProvider, PropertiesProvider}
import tech.beshu.ror.proxy

object Boot extends RorProxyApp

trait RorProxyApp extends IOApp
  with RorProxy
  with LogProxyBuildInfoMessage
  with Logging {

  implicit val provider: PropertiesProvider = JvmPropertiesProvider

  override def run(args: List[String]): IO[ExitCode] = {
    logLaunchingMessage()
    start(config())
      .flatMap {
        case Right(closeHandler) =>
          val proxyApp = Resource.make(IO(closeHandler))(handler =>
            IO.suspend(handler())
          )
          proxyApp
            .use { _ => IO.never }
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

  private def logLaunchingMessage(): Unit = {
    logger.info(proxy.launchingBanner)
    logBuildInfoMessage()
  }

  private def config() = {
    RorProperties.rorProxyConfigFile // ensure that ROR config is provided for proxy
    RorProxy.Config(
      proxyPort = RorProperties.rorProxyPort,
      esHost = RorProperties.rorProxyEsHost,
      esPort = RorProperties.rorProxyEsPort,
      esConfigFile = createElasticsearchYamlFileInTempDict(),
      superUserCredentials = readSuperUserCredentials()
    )
  }

  private def readSuperUserCredentials() = {
    val user = ProxyEnvSettings.rorSuperuserName
    val secret = ProxyEnvSettings.rorSuperUserSecret
    (user, secret) match {
      case (Some(u), Some(s)) => Some(Credentials(u, s))
      case (None, None)       => None
      case (Some(u), None)    => throw new IllegalArgumentException(s"Superuser name '${u.show}' defined, but no secret passed")
      case (None, Some(_))    => throw new IllegalArgumentException(s"Superuser password defined, but no user name passed")
    }
  }

  private def createElasticsearchYamlFileInTempDict() = {
    val tempDir = File.newTemporaryDirectory("ror").deleteOnExit()
    createTempFileWithContent(tempDir, "elasticsearch.yml", getContentOfElasticsearchYaml)
  }

  private def getContentOfElasticsearchYaml = {
    import tech.beshu.ror.utils.ScalaOps._
    new BufferedReader(new InputStreamReader(this.getClass.getResourceAsStream("/elasticsearch.yml")))
      .bracket { reader =>
        IOUtils.toString(reader)
      }
  }

  private def createTempFileWithContent(tempDir: File, tempFileName: String, content: String): File = {
    val tempFile = tempDir / tempFileName
    if(tempFile.exists) tempFile.delete()
    tempFile
      .createFile()
      .write(content)
      .deleteOnExit()
  }
}