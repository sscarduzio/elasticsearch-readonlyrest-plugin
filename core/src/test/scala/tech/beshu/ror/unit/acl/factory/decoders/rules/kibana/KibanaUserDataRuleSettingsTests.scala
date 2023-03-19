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
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.{IndexName, KibanaAccess, KibanaAllowedApiPath, KibanaApp, Regex, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.clusterIndexName

class KibanaUserDataRuleSettingsTests extends BaseRuleSettingsDecoderTest[KibanaUserDataRule] with OptionValues {

  "A KibanaUserDataRule" should {
    "be able to be loaded from config" when {
      "all required properties are set" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: "ro"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
            rule.settings.kibanaTemplateIndex should be(None)
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
          }
        )
      }
      "all other properties are set too" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana:
              |      access: "rw"
              |      kibana_index: ".kibana_custom"
              |      kibana_template_index: ".kibana_template"
              |      hide_apps: ["app1", "app2"]
              |      allowed_api_paths: ["^/api/spaces/.*$"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RW)
            rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana_custom")))
            rule.settings.kibanaTemplateIndex should be(Some(AlreadyResolved(clusterIndexName(".kibana_template"))))
            rule.settings.appsToHide should be(Set(KibanaApp("app1"), KibanaApp("app2")))
            rule.settings.allowedApiPaths should be(
              Set(KibanaAllowedApiPath(AllowedHttpMethod.Any, Regex.compile("""^/api/spaces/.*$""").get))
            )
            rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
          }
        )
      }
      "'access' property is set" when {
        "RO access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: ro
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "RW access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: rw
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RW)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "Admin access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: admin
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.Admin)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "'API only' access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: api_only
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.ApiOnly)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "'RO strict' access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: ro_strict
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.ROStrict)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "Unrestricted access is used" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: unrestricted
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.Unrestricted)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
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
                |
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
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set(KibanaApp("app1")))
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains two apps" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      hide_apps: [app1, app2]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set(KibanaApp("app1"), KibanaApp("app2")))
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains no apps" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
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
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
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
                |
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
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(AllowedHttpMethod.Any, Regex.compile("""^/api/spaces/.*$""").get))
              )
              rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it contains non-regex path" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
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
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(
                  AllowedHttpMethod.Any,
                  Regex.compile("""^/api/spaces\?test\=12\.2$""").get
                ))
              )
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "it uses specific http method" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths:
                |        - http_method: GET
                |          path: "/api/spaces?test=12.2"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(
                Set(KibanaAllowedApiPath(
                  AllowedHttpMethod.Specific(HttpMethod.Get),
                  Regex.compile("""^/api/spaces\?test\=12\.2$""").get
                ))
              )
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "there are several API paths defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    kibana:
                |      access: "ro"
                |      allowed_api_paths:
                |        - "^/api/spaces/.*$"
                |        - http_method: GET
                |          path: "/api/spaces?test=12.2"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.access should be(KibanaAccess.RO)
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set(
                KibanaAllowedApiPath(
                  AllowedHttpMethod.Any,
                  Regex.compile("""^/api/spaces/.*$""").get
                ),
                KibanaAllowedApiPath(
                  AllowedHttpMethod.Specific(HttpMethod.Get),
                  Regex.compile("""^/api/spaces\?test\=12\.2$""").get
                )
              ))
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
        "empty API paths array is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
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
              rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
              rule.settings.kibanaTemplateIndex should be(None)
              rule.settings.appsToHide should be(Set.empty)
              rule.settings.allowedApiPaths should be(Set.empty)
              rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
            }
          )
        }
      }
      "'kibana_index' property is defined with variable" in {
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
              |      kibana_index: "@{user}_kibana_index"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex shouldBe a [ToBeResolved[_]]
            rule.settings.kibanaTemplateIndex should be(None)
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
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
              |      kibana_template_index: "@{user}_kibana_template_index"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex should be(AlreadyResolved(clusterIndexName(".kibana")))
            rule.settings.kibanaTemplateIndex.value shouldBe a [ToBeResolved[_]]
            rule.settings.appsToHide should be(Set.empty)
            rule.settings.allowedApiPaths should be(Set.empty)
            rule.settings.rorIndex should be(RorConfigurationIndex( IndexName.Full(".readonlyrest")))
          }
        )
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
              |      kibana_index: ".kibana_custom"
              |      kibana_template_index: ".kibana_template"
              |      hide_apps: ["app1", "app2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """kibana:
                |  kibana_index: ".kibana_custom"
                |  kibana_template_index: ".kibana_template"
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
    }
  }
}
