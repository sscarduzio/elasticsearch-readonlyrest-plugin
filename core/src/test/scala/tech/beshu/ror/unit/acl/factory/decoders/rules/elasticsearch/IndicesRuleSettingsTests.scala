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

import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

class IndicesRuleSettingsTests extends BaseRuleSettingsDecoderTest[IndicesRule] {

  "An IndicesRule" should {
    "be able to be loaded from config" when {
      "one index is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: index1
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] = NonEmptySet.one(AlreadyResolved(clusterIndexName("index1").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "index is defined with user variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    indices: "index_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length should be (1)
            rule.settings.allowedIndices.head shouldBe a [ToBeResolved[_]]
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "one numeric index is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: "1"
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] = NonEmptySet.one(AlreadyResolved(clusterIndexName("1").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "one numeric index is defined as array" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: ["1"]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] = NonEmptySet.one(AlreadyResolved(clusterIndexName("1").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "two indices are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: [index1, index2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] =
              NonEmptySet.of(AlreadyResolved(clusterIndexName("index1").nel), AlreadyResolved(clusterIndexName("index2").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "two indices are defined, one is numeric" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: ["1", index2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] =
              NonEmptySet.of(AlreadyResolved(clusterIndexName("1").nel), AlreadyResolved(clusterIndexName("index2").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }

      "two numeric indices are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: ["1", "2"]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]] =
              NonEmptySet.of(AlreadyResolved(clusterIndexName("1").nel), AlreadyResolved(clusterIndexName("2").nel))
            rule.settings.allowedIndices should be(indices)
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "two indexes are defined, second one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    indices: [index1, "index_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length == 2

            rule.settings.allowedIndices.head should be(AlreadyResolved(clusterIndexName("index1").nel))
            rule.settings.allowedIndices.tail.head shouldBe a [ToBeResolved[_]]
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
      "extended mode is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    indices:
              |      patterns: [index1, "index_@{user}"]
              |      must_involve_indices: true
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length == 2

            rule.settings.allowedIndices.head should be(AlreadyResolved(clusterIndexName("index1").nel))
            rule.settings.allowedIndices.tail.head shouldBe a [ToBeResolved[_]]
            rule.settings.mustInvolveIndices shouldBe true
          }
        )
      }
      "extended mode is used but without `must_involve_indices` part" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    indices:
              |      patterns: [index1, "index_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length == 2

            rule.settings.allowedIndices.head should be(AlreadyResolved(clusterIndexName("index1").nel))
            rule.settings.allowedIndices.tail.head shouldBe a [ToBeResolved[_]]
            rule.settings.mustInvolveIndices shouldBe false
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no index is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """indices: null
                |""".stripMargin)))
          }
        )
      }
      "extended mode is used but with no indices" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices:
              |      patterns:
              |      must_involve_indices: true
              |
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """indices:
                |  patterns: null
                |  must_involve_indices: true
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
