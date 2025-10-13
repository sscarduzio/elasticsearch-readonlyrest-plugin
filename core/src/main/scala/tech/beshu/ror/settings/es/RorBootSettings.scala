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
import tech.beshu.ror.settings.es.RorBootSettings.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError
import tech.beshu.ror.utils.yaml.YamlLeafDecoder

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
        RorNotStartedResponse(notStartedHttpCode.getOrElse(RorNotStartedResponse.HttpCode.`403`)),
        RorFailedToStartResponse(failedToStartHttpCode.getOrElse(RorFailedToStartResponse.HttpCode.`403`))
      )

    private def rorNotStartedResponseHttpCodeDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[Option[RorNotStartedResponse.HttpCode]] = {
      implicit val ll = systemContext.propertiesProvider
      val creator: String => Either[String, RorNotStartedResponse.HttpCode] = {
        case "503" => Right(RorNotStartedResponse.HttpCode.`503`)
        case "403" => Right(RorNotStartedResponse.HttpCode.`403`)
        case unknown => Left(???)
      }
      implicit val l1: YamlLeafDecoder[RorNotStartedResponse.HttpCode] = YamlLeafDecoder.from(creator)
      implicit val l2: PropertyValueDecoder[RorNotStartedResponse.HttpCode] = PropertyValueDecoder.from(creator)
      YamlLeafOrPropertyDecoder(NonEmptyList.of(consts.rorSection, consts.rorNotStartedResponseCode))
    }

    private def rorRorFailedToStartResponseHttpCodeDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[Option[RorFailedToStartResponse.HttpCode]] = {
      implicit val ll = systemContext.propertiesProvider
      val creator: String => Either[String, RorFailedToStartResponse.HttpCode] = {
        case "503" => Right(RorFailedToStartResponse.HttpCode.`503`)
        case "403" => Right(RorFailedToStartResponse.HttpCode.`403`)
        case unknown => Left(???)
      }
      implicit val l1: YamlLeafDecoder[RorFailedToStartResponse.HttpCode] = YamlLeafDecoder.from(creator)
      implicit val l2: PropertyValueDecoder[RorFailedToStartResponse.HttpCode] = PropertyValueDecoder.from(creator)
      YamlLeafOrPropertyDecoder(NonEmptyList.of(consts.rorSection, consts.rorFailedTpStartResponseCode))
    }

//    private implicit val rorNotStartedResponseDecoder: Decoder[RorNotStartedResponse] = {
//      val segments = NonEmptyList.of(consts.rorSection, consts.rorNotStartedResponseCode)
//
//      implicit val httpCodeDecoder: Decoder[RorNotStartedResponse.HttpCode] = Decoder.decodeInt.emap {
//        case 403 => Right(RorNotStartedResponse.HttpCode.`403`)
//        case 503 => Right(RorNotStartedResponse.HttpCode.`503`)
//        case other => Left(
//          s"Unsupported response code [${other.show}] for ${segments.toList.mkString(".").show}. Supported response codes are: 403, 503."
//        )
//      }
//
//      YamlKeyDecoder[RorNotStartedResponse.HttpCode](
//        path = segments,
//        default = RorNotStartedResponse.HttpCode.`403`
//      )
//        .map(RorNotStartedResponse.apply)
//    }
//
//    private implicit val rorFailedToStartResponseDecoder: Decoder[RorFailedToStartResponse] = {
//      val segments = NonEmptyList.of(consts.rorSection, consts.rorFailedTpStartResponseCode)
//
//      implicit val httpCodeDecoder: Decoder[RorFailedToStartResponse.HttpCode] = Decoder.decodeInt.emap {
//        case 403 => Right(RorFailedToStartResponse.HttpCode.`403`)
//        case 503 => Right(RorFailedToStartResponse.HttpCode.`503`)
//        case other => Left(
//          s"Unsupported response code [${other.show}] for ${segments.toList.mkString(".").show}. Supported response codes are: 403, 503."
//        )
//      }
//
//      YamlKeyDecoder[RorFailedToStartResponse.HttpCode](
//        path = segments,
//        default = RorFailedToStartResponse.HttpCode.`403`
//      )
//        .map(RorFailedToStartResponse.apply)
//    }
  }

}
