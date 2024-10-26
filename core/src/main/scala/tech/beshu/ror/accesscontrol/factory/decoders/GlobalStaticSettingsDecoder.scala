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
package tech.beshu.ror.accesscontrol.factory.decoders

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

object GlobalStaticSettingsDecoder {

  def instance(rorConfigurationIndex: RorConfigurationIndex): Decoder[GlobalSettings] = {
    Decoder
      .instance { c =>
        for {
          basicAuthPrompt <- c.downField("prompt_for_basic_auth").as[Option[Boolean]]
          forbiddenMessage <- c.downField("response_if_req_forbidden").as[Option[String]]
          flsEngine <- c.downField("fls_engine").as[GlobalSettings.FlsEngine](flsEngineDecoder)
          caseMapping <- c.downField("username_case_sensitivity").as[CaseSensitivity]
        } yield new GlobalSettings(
          showBasicAuthPrompt = basicAuthPrompt.getOrElse(false),
          forbiddenRequestMessage = forbiddenMessage.getOrElse(GlobalSettings.defaultForbiddenRequestMessage),
          flsEngine = flsEngine,
          configurationIndex = rorConfigurationIndex,
          userIdCaseSensitivity = caseMapping
        )
      }
  }

  private def flsEngineDecoder: Decoder[GlobalSettings.FlsEngine] = {
    Decoder.decodeOption[String]
      .toSyncDecoder
      .emapE[GlobalSettings.FlsEngine] { flsEngineStr =>
        createFlsEngineForPlugin(flsEngineStr)
      }
      .decoder
  }

  implicit private val usernameCaseMappingDecoder: Decoder[CaseSensitivity] = {
    Decoder.decodeOption[String]
      .toSyncDecoder
      .emapE[CaseSensitivity] {
        case Some("case_insensitive") => Right(CaseSensitivity.Disabled)
        case Some("case_sensitive") | None => Right(CaseSensitivity.Enabled)
        case Some(other) => Left(CoreCreationError.GeneralReadonlyrestSettingsError(Message(
          s"Unknown username case mapping: '${other.show}'. Supported: 'case_insensitive', 'case_sensitive'(default)."
        )))
      }
      .decoder
  }

  private def createFlsEngineForPlugin(flsEngineFromConfig: Option[String]) = flsEngineFromConfig match {
    case None =>
      Right(GlobalSettings.FlsEngine.ESWithLucene)
    case Some(definedFlsEngine) =>
      flsEngineFromString(definedFlsEngine)
        .left
        .map { case () =>
          CoreCreationError.GeneralReadonlyrestSettingsError(Message(
            // we don't officially say the `lucene` is supported, that's why it is omitted in the error message
            s"Unknown fls engine: '${definedFlsEngine.show}'. Supported: 'es_with_lucene'(default), 'es'."
          ))
        }
  }

  private def flsEngineFromString(str: String): Either[Unit, FlsEngine] = str match {
    case "lucene" => Right(GlobalSettings.FlsEngine.Lucene)
    case "es_with_lucene" => Right(GlobalSettings.FlsEngine.ESWithLucene)
    case "es" => Right(GlobalSettings.FlsEngine.ES)
    case unknown => Left(())
  }

}
