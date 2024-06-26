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
package tech.beshu.ror.unit.acl.factory.decoders.rules.kibana

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.{EitherValues, OptionValues}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.ResolvableJsonRepresentationOps._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.Json.JsonValue.{BooleanValue, NullValue, NumValue, StringValue}
import tech.beshu.ror.accesscontrol.domain.Json.{JsonRepresentation, JsonTree}
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils._

class KibanaUserDataRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[KibanaUserDataRule]
    with OptionValues with EitherValues {

  "A KibanaUserDataRule" should {
    "be able to be loaded from config" when {
      "all required properties are set" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: "ro"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
            rule.settings.kibanaTemplateIndex should be(None)
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.metadata should be(None)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "all other properties are set too" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: "rw"
              |      index: ".kibana_custom"
              |      template_index: ".kibana_template"
              |      hide_apps: ["app1", "app2"]
              |      allowed_api_paths: ["^/api/spaces/.*$"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RW)
            rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana_custom")))
            rule.settings.kibanaTemplateIndex should be(Some(AlreadyResolved(kibanaIndexName(".kibana_template"))))
            rule.settings.appsToHide should be(Set(FullNameKibanaApp("app1"), FullNameKibanaApp("app2")))
            rule.settings.allowedApiPaths should be(
              Set(KibanaAllowedApiPath(AllowedHttpMethod.Any, JavaRegex.compile("""^/api/spaces/.*$""").get))
            )
            rule.settings.metadata should be(None)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "'access' property is set" when {
        "RO access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: ro
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "RW access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: rw
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RW)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "Admin access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: admin
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.Admin)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "'API only' access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: api_only
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.ApiOnly)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "'RO strict' access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: ro_strict
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.ROStrict)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "Unrestricted access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: unrestricted
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.Unrestricted)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
      }
      "'hide_apps' property is set" when {
        "it contains one app" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      hide_apps: app1
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set(FullNameKibanaApp("app1")))
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains two apps" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      hide_apps: [app1, "/^(?!(Analytics\\|Management).*$).*$/"]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set(
                FullNameKibanaApp("app1"),
                kibanaAppRegex("/^(?!(Analytics\\|Management).*$).*$/")
              ))
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains no apps" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      hide_apps: []
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
      }
      "'allowed_api_paths' property is set" when {
        "it contains regex path" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths: ["^/api/spaces/.*$"]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(AllowedHttpMethod.Any, JavaRegex.compile("""^/api/spaces/.*$""").get))
              )
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains non-regex path" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths: ["/api/spaces?test=12.2"]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(
                  AllowedHttpMethod.Any,
                  JavaRegex.compile("""^/api/spaces\?test\=12\.2$""").get
                ))
              )
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it uses specific http method" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths:
                |        - http_method: GET
                |          http_path: "/api/spaces?test=12.2"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(
                  AllowedHttpMethod.Specific(HttpMethod.Get),
                  JavaRegex.compile("""^/api/spaces\?test\=12\.2$""").get
                ))
              )
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "there are several API paths defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths:
                |        - "^/api/spaces/.*$"
                |        - http_method: GET
                |          http_path: "/api/spaces?test=12.2"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set(
                KibanaAllowedApiPath(
                  AllowedHttpMethod.Any,
                  JavaRegex.compile("""^/api/spaces/.*$""").get
                ),
                KibanaAllowedApiPath(
                  AllowedHttpMethod.Specific(HttpMethod.Get),
                  JavaRegex.compile("""^/api/spaces\?test\=12\.2$""").get
                )
              ))
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "empty API paths array is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths: []
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(None)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
      }
      "'kibana.index' property is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    kibana:
              |      access: "ro"
              |      index: "@{user}_kibana_index"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex shouldBe a[ToBeResolved[_]]
            rule.settings.kibanaTemplateIndex should be(None)
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.metadata should be(None)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "'kibana_template_index' property is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    kibana:
              |      access: "ro"
              |      template_index: "@{user}_kibana_template_index"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
            rule.settings.kibanaTemplateIndex.value shouldBe a[ToBeResolved[_]]
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.metadata should be(None)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "'metadata' property is defined" when {
        "its value is a JSON with all possible JSON types" in {
          val metadataJsonRepresentation: JsonRepresentation =
            JsonTree.Object(Map(
              "a" -> JsonTree.Value(NumValue(1)),
              "b" -> JsonTree.Value(BooleanValue(true)),
              "c" -> JsonTree.Value(StringValue("text")),
              "d" -> JsonTree.Array(
                JsonTree.Value(StringValue("a")) :: JsonTree.Value(StringValue("b")) :: Nil
              ),
              "e" -> JsonTree.Object(Map(
                "f" -> JsonTree.Value(NumValue(1))
              )),
              "g" -> JsonTree.Value(NullValue)
            ))

          val resolvableMetadataJsonRepresentation = metadataJsonRepresentation.toResolvable(variableCreator)
            .getOrElse(throw new IllegalStateException("Example metadata JSON should be resolvable"))
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    auth_key: user:pass
                |    kibana:
                |      access: "ro"
                |      metadata:
                |        a: 1
                |        b: true
                |        c: "text"
                |        d: [ "a","b" ]
                |        e:
                |         f: 1
                |        g: ~
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(Some(resolvableMetadataJsonRepresentation))
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "its value is NULL" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    auth_key: user:pass
                |    kibana:
                |      access: "ro"
                |      metadata: ~
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.metadata should be(Some(JsonTree.Value(AlreadyResolved(NullValue))))
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
      }
    }
    "not be able to be loaded from config" when {
      "some of the required properties are not set" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      index: ".kibana_custom"
              |      template_index: ".kibana_template"
              |      hide_apps: ["app1", "app2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """kibana:
                |  index: ".kibana_custom"
                |  template_index: ".kibana_template"
                |  hide_apps:
                |  - "app1"
                |  - "app2"
                |""".stripMargin)))
          }
        )
      }
      "'access' property value is unknown" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: unknown
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Unknown kibana access 'unknown'. Available options: 'ro', 'ro_strict', 'rw', 'api_only', 'admin', 'unrestricted'"
            )))
          }
        )
      }
      "kibana app is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: ro
              |      hide_apps: ["/^(?!(Analytics\\|Maps).*$.*$/"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Cannot compile [/^(?!(Analytics\\|Maps).*$.*$/] as a JS regex (https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Regular_expressions)"
            )))
          }
        )
      }
      "old style kibana rules are mixed with new style kibana rule" when {
        "kibana_access and kibana rules are mixed" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_access: ro
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                """The 'test_block' block contains 'kibana' rule and 'kibana_access' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
              )))
            }
          )
        }
        "kibana_index and kibana rules are mixed" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_index: .kibana_custom
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                """The 'test_block' block contains 'kibana' rule and 'kibana_index' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
              )))
            }
          )
        }
        "kibana_hide_apps and kibana rules are mixed" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_hide_apps: ["app1"]
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                """The 'test_block' block contains 'kibana' rule and 'kibana_hide_apps' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
              )))
            }
          )
        }
        "kibana_template_index and kibana rules are mixed" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_template_index: ".kibana_template_index"
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                """The 'test_block' block contains 'kibana' rule and 'kibana_template_index' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
              )))
            }
          )
        }
      }
      "it's defined with other rule in the block" when {
        "the rule is 'actions' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |    actions: ["indices:data/write/*"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'actions' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'filter' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |    filter: "{\"bool\": {\"must\": [{\"term\": {\"title\": {\"value\": \"a1\"}}}]}}"
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'filter' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'fields' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |    fields: ["_source","user1"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'fields' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'response_fields' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana:
                |      access: ro
                |      index: .kibana_custom
                |    response_fields: ["hits.hits"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'response_fields' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
      }
    }
  }

  private lazy val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
