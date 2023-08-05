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
package tech.beshu.ror.configuration

import better.files.File
import cats.data.NonEmptyList
import io.circe.Decoder
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.RorBootConfiguration.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import java.io.{File => JFile}
import java.nio.file.Path

final case class RorBootConfiguration(rorNotStartedResponse: RorNotStartedResponse,
                                      rorFailedToStartResponse: RorFailedToStartResponse)

object RorBootConfiguration extends Logging {

  def load(esConfigFolderPath: Path)
          (implicit environmentConfig: EnvironmentConfig): Task[Either[MalformedSettings, RorBootConfiguration]] = Task {
    val esConfig = File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath)
    implicit val rorBootConfigurationDecoder: Decoder[RorBootConfiguration] = Decoders.decoder
    loadRorBootstrapConfig(esConfig)
  }

  private def loadRorBootstrapConfig(configFile: File)
                                    (implicit decoder: Decoder[RorBootConfiguration],
                                     environmentConfig: EnvironmentConfig) = {
    new YamlFileBasedConfigLoader(configFile).loadConfig[RorBootConfiguration](configName = "ROR boot configuration")
  }

  final case class RorNotStartedResponse(httpCode: RorNotStartedResponse.HttpCode)
  object RorNotStartedResponse {
    sealed trait HttpCode
    object HttpCode {
      case object `403` extends HttpCode
      case object `503` extends HttpCode
    }
  }

  final case class RorFailedToStartResponse(httpCode: RorFailedToStartResponse.HttpCode)
  object RorFailedToStartResponse {
    sealed trait HttpCode
    object HttpCode {
      case object `403` extends HttpCode
      case object `503` extends HttpCode
    }
  }
}

private object Decoders extends Logging {

  object consts {
    val rorSection = "readonlyrest"
    val rorNotStartedResponseCode = "not_started_response_code"
    val rorFailedTpStartResponseCode = "failed_to_start_response_code"
  }

  def decoder: Decoder[RorBootConfiguration] = Decoder.instance { c =>
    for {
      notStarted <- c.as[RorNotStartedResponse]
      failedToStart <- c.as[RorFailedToStartResponse]
    } yield RorBootConfiguration(notStarted, failedToStart)
  }

  private implicit val rorNotStartedResponseDecoder: Decoder[RorNotStartedResponse] = {
    val segments = NonEmptyList.of(consts.rorSection, consts.rorNotStartedResponseCode)

    implicit val httpCodeDecoder: Decoder[RorNotStartedResponse.HttpCode] = Decoder.decodeInt.emap {
      case 403 => Right(RorNotStartedResponse.HttpCode.`403`)
      case 503 => Right(RorNotStartedResponse.HttpCode.`503`)
      case other => Left(
        s"Unsupported response code [$other] for ${segments.toList.mkString(".")}. Supported response codes are: 403, 503."
      )
    }

    YamlKeyDecoder[RorNotStartedResponse.HttpCode](
      segments = segments,
      default = RorNotStartedResponse.HttpCode.`403`
    )
      .map(RorNotStartedResponse.apply)
  }

  private implicit val rorFailedToStartResponseDecoder: Decoder[RorFailedToStartResponse] = {
    val segments = NonEmptyList.of(consts.rorSection, consts.rorFailedTpStartResponseCode)

    implicit val httpCodeDecoder: Decoder[RorFailedToStartResponse.HttpCode] = Decoder.decodeInt.emap {
      case 403 => Right(RorFailedToStartResponse.HttpCode.`403`)
      case 503 => Right(RorFailedToStartResponse.HttpCode.`503`)
      case other => Left(
        s"Unsupported response code [$other] for ${segments.toList.mkString(".")}. Supported response codes are: 403, 503."
      )
    }

    YamlKeyDecoder[RorFailedToStartResponse.HttpCode](
      segments = segments,
      default = RorFailedToStartResponse.HttpCode.`403`
    )
      .map(RorFailedToStartResponse.apply)
  }
}
