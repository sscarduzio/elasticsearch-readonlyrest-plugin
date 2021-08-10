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
import tech.beshu.ror.accesscontrol.domain.{RorAuditIndexTemplate, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.boot.RorMode

object GlobalStaticSettingsDecoder {

  def instance(rorMode: RorMode,
               rorConfigurationIndex: RorConfigurationIndex,
               rorAuditIndexTemplate: Option[RorAuditIndexTemplate]): Decoder[GlobalSettings] = {
    Decoder
      .instance { c =>
        for {
          basicAuthPrompt <- c.downField("prompt_for_basic_auth").as[Option[Boolean]]
          forbiddenMessage <- c.downField("response_if_req_forbidden").as[Option[String]]
          flsEngine <- c.downField("fls_engine").as[GlobalSettings.FlsEngine](flsEngineDecoder(rorMode))
          caseMapping <- c.downField("username_case_sensitivity").as[GlobalSettings.UsernameCaseMapping]
        } yield new GlobalSettings(
          basicAuthPrompt.getOrElse(true),
          forbiddenMessage.getOrElse(GlobalSettings.defaultForbiddenRequestMessage),
          flsEngine,
          rorConfigurationIndex,
          caseMapping,
          rorAuditIndexTemplate
        )
      }
  }

  private def flsEngineDecoder(rorMode: RorMode): Decoder[GlobalSettings.FlsEngine] = {
    Decoder.decodeOption[String]
      .toSyncDecoder
      .emapE[GlobalSettings.FlsEngine] { flsEngineStr =>
        rorMode match {
          case RorMode.Plugin =>
            createFlsEngineForPlugin(flsEngineStr)
          case RorMode.Proxy =>
            createFlsEngineForProxy(flsEngineStr)
        }
      }
      .decoder
  }

  implicit private val usernameCaseMappingDecoder: Decoder[GlobalSettings.UsernameCaseMapping] = {
    Decoder.decodeOption[String]
      .toSyncDecoder
      .emap[GlobalSettings.UsernameCaseMapping] {
        case Some("case_insensitive") => Right(GlobalSettings.UsernameCaseMapping.CaseInsensitive)
        case Some("case_sensitive") | None => Right(GlobalSettings.UsernameCaseMapping.CaseSensitive)
        case Some(other) => Left(s"Unknown username case mapping: '$other'. Supported: 'case_insensitive', 'case_sensitive'(default).")
      }
      .decoder
  }

  private def createFlsEngineForPlugin(flsEngineFromConfig: Option[String]) = flsEngineFromConfig match {
    case None =>
      Right(GlobalSettings.FlsEngine.ESWithLucene)
    case Some(definedFlsEngine) =>
      flsEngineFromString(definedFlsEngine)
        .left
        .map(unknown => AclCreationError.GeneralReadonlyrestSettingsError(Message(s"Unknown fls engine: '${unknown.value}'. Supported: 'es_with_lucene', 'es'.")))
  }

  private def createFlsEngineForProxy(flsEngineFromConfig: Option[String]) = flsEngineFromConfig match {
    case None =>
      Right(GlobalSettings.FlsEngine.ES)
    case Some(definedFlsEngine) =>
      flsEngineFromString(definedFlsEngine) match {
        case Right(FlsEngine.ES) =>
          Right(FlsEngine.ES)
        case _ =>
          Left(AclCreationError.GeneralReadonlyrestSettingsError(Message(s"Fls engine: '$definedFlsEngine' is not allowed for ROR proxy")))
      }
  }

  private def flsEngineFromString(str: String): Either[UnknownFlsEngine, FlsEngine] = str match {
    case "lucene" => Right(GlobalSettings.FlsEngine.Lucene)
    case "es_with_lucene" => Right(GlobalSettings.FlsEngine.ESWithLucene)
    case "es" => Right(GlobalSettings.FlsEngine.ES)
    case unknown => Left(UnknownFlsEngine(unknown))
  }

  private final case class UnknownFlsEngine(value: String)

}
