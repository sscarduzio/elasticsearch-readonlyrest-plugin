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
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.optionalDecoder

object GlobalStaticSettingsDecoder {

  private val globalSettingsSectionName = "global_settings"

  def instance(rorConfigurationIndex: RorSettingsIndex): Decoder[GlobalSettings] = {
    for {
      showBasicAuthPrompt <- decoderFor[Boolean]("prompt_for_basic_auth")
      forbiddenRequestMessage <- decoderFor[String]("response_if_req_forbidden")
      flsEngine <- decoderFor[FlsEngine]("fls_engine")
      userIdCaseSensitivity <- decoderFor[CaseSensitivity]("username_case_sensitivity")
      usersDefinitionDuplicateUsernamesValidationEnabled <- decoderFor[Boolean]("users_section_duplicate_usernames_detection")
    } yield GlobalSettings(
      showBasicAuthPrompt.getOrElse(false),
      forbiddenRequestMessage.getOrElse(GlobalSettings.defaultForbiddenRequestMessage),
      flsEngine.getOrElse(GlobalSettings.FlsEngine.ESWithLucene),
      rorConfigurationIndex,
      userIdCaseSensitivity.getOrElse(CaseSensitivity.Enabled),
      usersDefinitionDuplicateUsernamesValidationEnabled.getOrElse(true)
    )
  }

  private implicit val flsEngineDecoder: Decoder[FlsEngine] = {
    Decoder.decodeString
      .toSyncDecoder
      .emapE[FlsEngine] {
        case "lucene" => Right(GlobalSettings.FlsEngine.Lucene)
        case "es_with_lucene" => Right(GlobalSettings.FlsEngine.ESWithLucene)
        case "es" => Right(GlobalSettings.FlsEngine.ES)
        case unknown => Left(CoreCreationError.GeneralReadonlyrestSettingsError(Message(
          // we don't officially say the `lucene` is supported, that's why it is omitted in the error message
          s"Unknown fls engine: '$unknown'. Supported: 'es_with_lucene'(default), 'es'."
        )))
      }
      .decoder
  }

  implicit private val usernameCaseMappingDecoder: Decoder[CaseSensitivity] = {
    Decoder.decodeString
      .toSyncDecoder
      .emapE[CaseSensitivity] {
        case "case_insensitive" => Right(CaseSensitivity.Disabled)
        case "case_sensitive" => Right(CaseSensitivity.Enabled)
        case unknown => Left(CoreCreationError.GeneralReadonlyrestSettingsError(Message(
          s"Unknown username case mapping: '$unknown'. Supported: 'case_insensitive', 'case_sensitive'(default)."
        )))
      }
      .decoder
  }

  private def decoderFor[T: Decoder](globalSettingKey: String): Decoder[Option[T]] = {
    optionalDecoder[T](
      fieldsPath = globalSettingsSectionName :: globalSettingKey :: Nil,
      deprecatedFieldsPath = globalSettingKey :: Nil
    )
  }

}
