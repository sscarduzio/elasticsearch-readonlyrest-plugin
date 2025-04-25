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
package tech.beshu.ror.unit.acl.factory.decoders.definitions

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.GeneralReadonlyrestSettingsError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.GlobalStaticSettingsDecoder
import tech.beshu.ror.accesscontrol.utils.{SyncDecoder, SyncDecoderCreator}

class GlobalSettingsTests extends
  BaseDecoderTest(GlobalSettingsTests.decoder) {

  "A global settings should be able to be loaded from config (in the 'readonlyrest.global_settings' section level)" when {
    "'prompt_for_basic_auth'" should {
      "be decoded with success" when {
        "enabled" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   prompt_for_basic_auth: true
               """.stripMargin,
            assertion = config =>
              config.showBasicAuthPrompt should be(true)
          )
        }
        "disabled" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   prompt_for_basic_auth: false
               """.stripMargin,
            assertion = config =>
              config.showBasicAuthPrompt should be(false)
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.showBasicAuthPrompt should be(false)
          )
        }
      }
    }
    "'response_if_req_forbidden'" should {
      "be decoded with success" when {
        "custom message" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   response_if_req_forbidden: custom_forbidden_response
               """.stripMargin,
            assertion = config =>
              config.forbiddenRequestMessage should be("custom_forbidden_response")
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.forbiddenRequestMessage should be("Forbidden by ReadonlyREST")
          )
        }
      }
    }
    "'fls_engine'" should {
      "be decoded with success" when {
        "lucene" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   fls_engine: lucene
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.Lucene)
          )
        }
        "es_with_lucene" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   fls_engine: es_with_lucene
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ESWithLucene)
          )
        }
        "es" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   fls_engine: es
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ES)
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ESWithLucene)
          )
        }
      }
      "be decoded with failure" when {
        "unknown engine type" in {
          assertDecodingFailure(
            yaml =
              s"""
                 | global_settings:
                 |   fls_engine: custom
               """.stripMargin,
            assertion =
              error =>
                error should be(GeneralReadonlyrestSettingsError(Message(
                  "Unknown fls engine: 'custom'. Supported: 'es_with_lucene'(default), 'es'.")
                ))
          )
        }
      }
    }
    "'username_case_sensitivity'" should {
      "be decoded with success" when {
        "case sensitive" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   username_case_sensitivity: case_sensitive
               """.stripMargin,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Enabled)
          )
        }
        "case insensitive" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | global_settings:
                 |   username_case_sensitivity: case_insensitive
               """.stripMargin,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Disabled)
          )
        }
        "no defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Enabled)
          )
        }
      }
      "be decoded with failure" when {
        "unknown type" in {
          assertDecodingFailure(
            yaml =
              s"""
                 | global_settings:
                 |   username_case_sensitivity: custom
               """.stripMargin,
            assertion =
              error =>
                error should be(GeneralReadonlyrestSettingsError(Message(
                  "Unknown username case mapping: 'custom'. Supported: 'case_insensitive', 'case_sensitive'(default).")
                ))
          )
        }
      }
    }
  }

  "A global settings should be able to be loaded from config (in the 'readonlyrest' section level)" when {
    "'prompt_for_basic_auth'" should {
      "be decoded with success" when {
        "enabled" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | prompt_for_basic_auth: true
               """.stripMargin,
            assertion = config =>
              config.showBasicAuthPrompt should be(true)
          )
        }
        "disabled" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | prompt_for_basic_auth: false
               """.stripMargin,
            assertion = config =>
              config.showBasicAuthPrompt should be(false)
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.showBasicAuthPrompt should be(false)
          )
        }
      }
    }
    "'response_if_req_forbidden'" should {
      "be decoded with success" when {
        "custom message" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | response_if_req_forbidden: custom_forbidden_response
               """.stripMargin,
            assertion = config =>
              config.forbiddenRequestMessage should be("custom_forbidden_response")
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.forbiddenRequestMessage should be("Forbidden by ReadonlyREST")
          )
        }
      }
    }
    "'fls_engine'" should {
      "be decoded with success" when {
        "lucene" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | fls_engine: lucene
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.Lucene)
          )
        }
        "es_with_lucene" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | fls_engine: es_with_lucene
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ESWithLucene)
          )
        }
        "es" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | fls_engine: es
               """.stripMargin,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ES)
          )
        }
        "not defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.flsEngine should be(FlsEngine.ESWithLucene)
          )
        }
      }
      "be decoded with failure" when {
        "unknown engine type" in {
          assertDecodingFailure(
            yaml =
              s"""
                 | fls_engine: custom
               """.stripMargin,
            assertion =
              error =>
                error should be(GeneralReadonlyrestSettingsError(Message(
                  "Unknown fls engine: 'custom'. Supported: 'es_with_lucene'(default), 'es'.")
                ))
          )
        }
      }
    }
    "'username_case_sensitivity'" should {
      "be decoded with success" when {
        "case sensitive" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | username_case_sensitivity: case_sensitive
               """.stripMargin,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Enabled)
          )
        }
        "case insensitive" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 | username_case_sensitivity: case_insensitive
               """.stripMargin,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Disabled)
          )
        }
        "no defined" in {
          assertDecodingSuccess(
            yaml = noCustomSettingsYaml,
            assertion = config =>
              config.userIdCaseSensitivity should be(CaseSensitivity.Enabled)
          )
        }
      }
      "be decoded with failure" when {
        "unknown type" in {
          assertDecodingFailure(
            yaml =
              s"""
                 | username_case_sensitivity: custom
               """.stripMargin,
            assertion =
              error =>
                error should be(GeneralReadonlyrestSettingsError(Message(
                  "Unknown username case mapping: 'custom'. Supported: 'case_insensitive', 'case_sensitive'(default).")
                ))
          )
        }
      }
    }
  }

  "The deprecated and the new syntax of global settings cannot be mixed" when {
    "it's 'prompt_for_basic_auth'" in {
      assertDecodingFailure(
        yaml =
          s"""
             | global_settings:
             |   prompt_for_basic_auth: true
             | prompt_for_basic_auth: true
               """.stripMargin,
        assertion =
          error =>
            error should be(GeneralReadonlyrestSettingsError(Message(
              "Detected duplicated settings (usage of current and deprecated syntax). You cannot use '.global_settings.prompt_for_basic_auth' together with '.prompt_for_basic_auth'. Pick one syntax."
            )))
      )
    }
    "it's 'response_if_req_forbidden'" in {
      assertDecodingFailure(
        yaml =
          s"""
             | response_if_req_forbidden: "forbidden"
             | global_settings:
             |   response_if_req_forbidden: "forbidden"
                """.stripMargin,
        assertion =
          error =>
            error should be(GeneralReadonlyrestSettingsError(Message(
              "Detected duplicated settings (usage of current and deprecated syntax). You cannot use '.global_settings.response_if_req_forbidden' together with '.response_if_req_forbidden'. Pick one syntax."
            )))
      )
    }
    "it's 'fls_engine'" in {
      assertDecodingFailure(
        yaml =
          s"""
             | fls_engine: "es"
             | global_settings:
             |   fls_engine: "es"
                      """.stripMargin,
        assertion =
          error =>
            error should be(GeneralReadonlyrestSettingsError(Message(
              "Detected duplicated settings (usage of current and deprecated syntax). You cannot use '.global_settings.fls_engine' together with '.fls_engine'. Pick one syntax."
            )))
      )
    }
    "it's 'username_case_sensitivity'" in {
      assertDecodingFailure(
        yaml =
          s"""
             | username_case_sensitivity: "case_insensitive"
             | global_settings:
             |   username_case_sensitivity: "case_insensitive"
                            """.stripMargin,
        assertion =
          error =>
            error should be(GeneralReadonlyrestSettingsError(Message(
              "Detected duplicated settings (usage of current and deprecated syntax). You cannot use '.global_settings.username_case_sensitivity' together with '.username_case_sensitivity'. Pick one syntax."
            )))
      )
    }
  }

  private lazy val noCustomSettingsYaml =
    s"""
       | rules:
       |""".stripMargin

}

private object GlobalSettingsTests {
  val decoder: SyncDecoder[GlobalSettings] = SyncDecoderCreator.from(
    GlobalStaticSettingsDecoder.instance(RorConfigurationIndex(IndexName.Full(NonEmptyString.unsafeFrom(".readonlyrest"))))
  )
}
