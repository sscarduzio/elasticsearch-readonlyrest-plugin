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
package tech.beshu.ror.settings.es

import cats.data.NonEmptyList
import eu.timepit.refined.types.all.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.settings.es.RorBootSettings.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyDecoder

import scala.language.postfixOps

final case class RorBootSettings(rorNotStartedResponse: RorNotStartedResponse,
                                 rorFailedToStartResponse: RorFailedToStartResponse)

object RorBootSettings extends YamlFileBasedSettingsLoaderSupport {

  def load(env: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, RorBootSettings]] = {
    implicit val rorBootSettingsDecoder: YamlLeafOrPropertyDecoder[RorBootSettings] = decoders.rorBootSettingsDecoder(systemContext)
    loadSetting[RorBootSettings](env, "ROR boot settings")
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

  private object decoders {

    object defaults {
      val rorNotStartedResponse: RorNotStartedResponse.HttpCode = RorNotStartedResponse.HttpCode.`403`
      val rorFailedToStartResponse: RorFailedToStartResponse.HttpCode = RorFailedToStartResponse.HttpCode.`403`
    }

    object consts {
      val rorSection: NonEmptyString = NonEmptyString.unsafeFrom("readonlyrest")
      val rorNotStartedResponseCode: NonEmptyString = NonEmptyString.unsafeFrom("not_started_response_code")
      val rorFailedTpStartResponseCode: NonEmptyString = NonEmptyString.unsafeFrom("failed_to_start_response_code")
    }

    def rorBootSettingsDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[RorBootSettings] =
      for {
        notStartedHttpCode <- rorNotStartedResponseHttpCodeDecoder(systemContext)
        failedToStartHttpCode <- rorRorFailedToStartResponseHttpCodeDecoder(systemContext)
      } yield RorBootSettings(
        RorNotStartedResponse(notStartedHttpCode.getOrElse(defaults.rorNotStartedResponse)),
        RorFailedToStartResponse(failedToStartHttpCode.getOrElse(defaults.rorFailedToStartResponse))
      )

    private def rorNotStartedResponseHttpCodeDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[Option[RorNotStartedResponse.HttpCode]] = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val path = s"${consts.rorSection}.${consts.rorNotStartedResponseCode}"
      val creator: String => Either[String, RorNotStartedResponse.HttpCode] = {
        case "503" => Right(RorNotStartedResponse.HttpCode.`503`)
        case "403" => Right(RorNotStartedResponse.HttpCode.`403`)
        case unknown => Left(s"Unsupported HTTP code '$unknown' for '$path'. Allowed values: '403', '503'")
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.rorNotStartedResponseCode),
        creator = creator
      )
    }

    private def rorRorFailedToStartResponseHttpCodeDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[Option[RorFailedToStartResponse.HttpCode]] = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val path = s"${consts.rorSection}.${consts.rorFailedTpStartResponseCode}"
      val creator: String => Either[String, RorFailedToStartResponse.HttpCode] = {
        case "503" => Right(RorFailedToStartResponse.HttpCode.`503`)
        case "403" => Right(RorFailedToStartResponse.HttpCode.`403`)
        case unknown => Left(s"Unsupported HTTP code '$unknown' for '$path'. Allowed values: '403', '503'")
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.rorFailedTpStartResponseCode),
        creator = creator
      )
    }
  }

}
