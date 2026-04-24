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
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.settings.es.RorBootSettings.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.utils.FromString
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyOrEnvDecoder

import scala.language.postfixOps

final case class RorBootSettings(rorNotStartedResponse: RorNotStartedResponse,
                                 rorFailedToStartResponse: RorFailedToStartResponse)

object RorBootSettings extends ElasticsearchConfigLoaderSupport {

  def load(env: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, RorBootSettings]] = {
    implicit val rorBootSettingsDecoder: YamlLeafOrPropertyOrEnvDecoder[RorBootSettings] = decoders.rorBootSettingsDecoder(systemContext)
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
      val rorFailedToStartResponseCode: NonEmptyString = NonEmptyString.unsafeFrom("failed_to_start_response_code")
    }

    def rorBootSettingsDecoder(systemContext: SystemContext): YamlLeafOrPropertyOrEnvDecoder[RorBootSettings] = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider
      for {
        notStartedHttpCode    <- httpCodeDecoder(consts.rorNotStartedResponseCode,    RorNotStartedResponse.HttpCode.`403`,    RorNotStartedResponse.HttpCode.`503`)
        failedToStartHttpCode <- httpCodeDecoder(consts.rorFailedToStartResponseCode, RorFailedToStartResponse.HttpCode.`403`, RorFailedToStartResponse.HttpCode.`503`)
      } yield RorBootSettings(
        RorNotStartedResponse(notStartedHttpCode.getOrElse(defaults.rorNotStartedResponse)),
        RorFailedToStartResponse(failedToStartHttpCode.getOrElse(defaults.rorFailedToStartResponse))
      )
    }

    private def httpCodeDecoder[T](pathKey: NonEmptyString, code403: T, code503: T)
                                  (implicit propertiesProvider: PropertiesProvider,
                                   envVarsProvider: EnvVarsProvider): YamlLeafOrPropertyOrEnvDecoder[Option[T]] =
      YamlLeafOrPropertyOrEnvDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, pathKey),
        decoder = FromString.instance {
          case "403"    => Right(code403)
          case "503"    => Right(code503)
          case unknown  => Left(s"Unsupported HTTP code '$unknown'. Allowed values: '403', '503'")
        }
      )
  }

}
