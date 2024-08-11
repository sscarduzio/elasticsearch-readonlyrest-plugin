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
package tech.beshu.ror.unit.acl.factory.decoders.rules.elasticsearch

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{GeneralReadonlyrestSettingsError, RulesLevelCreationError}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils._

class FieldsRuleSettingsTest extends BaseRuleSettingsDecoderTest[FieldsRule] {

  "A FieldsRule" should {
    "be able to be loaded from config" when {
      "ror is run in plugin mode and" when {
        "only one field is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: "field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
              rule.settings.flsEngine should be(FlsEngine.ESWithLucene)
            }
          )
        }
        "two fields are defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: [field1, field2]
                |
                |""".stripMargin,
            assertion = rule => {
              val decodedFields = rule.settings.fields
              decodedFields.head should be(AlreadyResolved(DocumentField("field1").nel))
              decodedFields.last should be(AlreadyResolved(DocumentField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
              rule.settings.flsEngine should be(FlsEngine.ESWithLucene)
            }
          )
        }
        "only one blacklisted field is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: "~field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Blacklist)
              rule.settings.flsEngine should be(FlsEngine.ESWithLucene)
            }
          )
        }
        "two blacklisted fields are defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: [~field1, ~field2]
                |
                |""".stripMargin,
            assertion = rule => {
              val decodedFields = rule.settings.fields
              decodedFields.head should be(AlreadyResolved(DocumentField("field1").nel))
              decodedFields.last should be(AlreadyResolved(DocumentField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Blacklist)
              rule.settings.flsEngine should be(FlsEngine.ESWithLucene)
            }
          )
        }
        "field is defined with variable" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: ["@{user}", "field2"]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.fields.head shouldBe a[ToBeResolved[_]]
              rule.settings.fields.last should be(AlreadyResolved(DocumentField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
              rule.settings.flsEngine should be(FlsEngine.ESWithLucene)
            }
          )
        }
        "'lucene' fls engine is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  fls_engine: "lucene"
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: "field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
              rule.settings.flsEngine should be(FlsEngine.Lucene)
            }
          )
        }
        "'es' fls engine is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |
                |  fls_engine: "es"
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: "field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
              rule.settings.flsEngine should be(FlsEngine.ES)
            }
          )
        }
      }
    }
    "not be able to be loaded from config" when {
      "no field is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """fields: null
                |""".stripMargin
            )))
          }
        )
      }
      "blacklisted fields are mixed with whitelisted ones" when {
        "no variable is used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: [field1, ~field2]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: 'field1','~field2'"
              )))
            }
          )
        }
        "variable and already resolved value are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: ["@{user}", "~field2"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~field2'"
              )))
            }
          )
        }
        "only variables are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: ["@{user}", "~@{user}.name"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~@{user}.name'"
              )))
            }
          )
        }
      }
      "disallowed fields are defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [field1, _type, _uid]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "These fields cannot be filtered using this rule: _type,_uid"
            )))
          }
        )
      }
      "unknown fls engine is used" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  fls_engine: "something"
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: "field1"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(GeneralReadonlyrestSettingsError(Message("Unknown fls engine: 'something'. Supported: 'lucene', 'es', 'es_with_lucene'(default).")))
          }
        )
      }
    }
  }
}
