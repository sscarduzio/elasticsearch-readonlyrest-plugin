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
package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.acl.blocks.rules.SnapshotsRule
import tech.beshu.ror.acl.blocks.values._
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._

class SnapshotsRuleSettingsTest extends BaseRuleSettingsDecoderTest[SnapshotsRule] {

  "A SnapshotsRule" should {
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
              |    snapshots: index1
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[Variable[IndexName]] = NonEmptySet.one(AlreadyResolved(IndexName("index1")))
            rule.settings.allowedIndices should be(indices)
          }
        )
      }
      "index is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: "index_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length should be (1)
            rule.settings.allowedIndices.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
      "two indexes are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: [index1, index2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[Variable[IndexName]] = NonEmptySet.of(AlreadyResolved(IndexName("index1")), AlreadyResolved(IndexName("index2")))
            rule.settings.allowedIndices should be(indices)
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
              |    snapshots: [index1, "index_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedIndices.length == 2

            rule.settings.allowedIndices.head should be(AlreadyResolved(IndexName("index1")))
            rule.settings.allowedIndices.tail.head shouldBe a [ToBeResolved[_]]
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
              |    snapshots:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """snapshots: null
                |""".stripMargin)))
          }
        )
      }
      "there is '_all' index defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: [index1, _all]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (snapshots) that matches all the values is redundant - index _all"
            )))
          }
        )
      }
      "there is '*' index defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: ["index1", "*", "index2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (snapshots) that matches all the values is redundant - index *"
            )))
          }
        )
      }
    }
  }
}
