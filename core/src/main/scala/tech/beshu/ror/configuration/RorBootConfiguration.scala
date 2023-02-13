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
import tech.beshu.ror.providers.EnvVarsProvider

import java.io.{File => JFile}
import java.nio.file.Path

final case class RorBootConfiguration(rorNotStartedResponse: RorNotStartedResponse,
                                      rorFailedToStartResponse: RorFailedToStartResponse)

object RorBootConfiguration extends Logging {

  val default: RorBootConfiguration = RorBootConfiguration(
    rorNotStartedResponse = RorNotStartedResponse.default,
    rorFailedToStartResponse = RorFailedToStartResponse.default
  )

  def load(esConfigFolderPath: Path)
          (implicit envVarsProvider: EnvVarsProvider): Task[Either[MalformedSettings, RorBootConfiguration]] = Task {
    val esConfig = File(new JFile(esConfigFolderPath.toFile, "elasticsearch.yml").toPath)
    implicit val sslDecoder: Decoder[RorBootConfiguration] = Decoders.decoder
    loadRorBootstrapConfig(esConfig)
  }

  private def loadRorBootstrapConfig(configFile: File)
                                    (implicit decoder: Decoder[RorBootConfiguration],
                                     envVarsProvider: EnvVarsProvider) = {
    new YamlFileBasedConfigLoader(configFile).loadConfig[RorBootConfiguration](configName = "ROR boot configuration")
  }

  final case class RorNotStartedResponse(httpCode: RorNotStartedResponse.HttpCode)

  object RorNotStartedResponse {
    val default: RorNotStartedResponse = RorNotStartedResponse(HttpCode.`403`)

    sealed trait HttpCode
    object HttpCode {
      case object `403` extends HttpCode
      case object `503` extends HttpCode
    }
  }

  final case class RorFailedToStartResponse(httpCode: RorFailedToStartResponse.HttpCode)

  object RorFailedToStartResponse {
    val default: RorFailedToStartResponse = RorFailedToStartResponse(httpCode = HttpCode.`403`)

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

  private def oneLiner(head: String, tail: String*) = {
    NonEmptyList.of[String](head, tail: _*).toList.mkString(".")
  }

  private implicit val rorNotStartedResponseDecoder: Decoder[RorNotStartedResponse] = {
    implicit val httpCodeDecoder: Decoder[RorNotStartedResponse.HttpCode] = Decoder.decodeInt.emap {
      case 403 => Right(RorNotStartedResponse.HttpCode.`403`)
      case 503 => Right(RorNotStartedResponse.HttpCode.`503`)
      case other => Left(
        s"Unsupported response code [$other] for ${consts.rorNotStartedResponseCode}. Supported response codes are: 403, 503."
      )
    }

    Decoder.instance { c =>
      for {
        oneLine <- c
          .downField(oneLiner(consts.rorSection, consts.rorNotStartedResponseCode))
          .as[Option[RorNotStartedResponse.HttpCode]]
        twoLines <- c
          .downField(consts.rorSection)
          .downField(consts.rorNotStartedResponseCode)
          .as[Option[RorNotStartedResponse.HttpCode]]
      } yield {
        oneLine
          .orElse(twoLines)
          .map(RorNotStartedResponse.apply)
          .getOrElse(RorNotStartedResponse.default)
      }
    }
  }

  private implicit val rorFailedToStartResponseDecoder: Decoder[RorFailedToStartResponse] = {
    implicit val httpCodeDecoder: Decoder[RorFailedToStartResponse.HttpCode] = Decoder.decodeInt.emap {
      case 403 => Right(RorFailedToStartResponse.HttpCode.`403`)
      case 503 => Right(RorFailedToStartResponse.HttpCode.`503`)
      case other => Left(
        s"Unsupported response code [$other] for ${consts.rorFailedTpStartResponseCode}. Supported response codes are: 403, 503."
      )
    }

    Decoder.instance { c =>
      for {
        oneLine <- c
          .downField(oneLiner(consts.rorSection, consts.rorFailedTpStartResponseCode))
          .as[Option[RorFailedToStartResponse.HttpCode]]
        twoLines <- c
          .downField(consts.rorSection)
          .downField(consts.rorFailedTpStartResponseCode)
          .as[Option[RorFailedToStartResponse.HttpCode]]
      } yield {
        oneLine
          .orElse(twoLines)
          .map(RorFailedToStartResponse.apply)
          .getOrElse(RorFailedToStartResponse.default)
      }
    }
  }
}
