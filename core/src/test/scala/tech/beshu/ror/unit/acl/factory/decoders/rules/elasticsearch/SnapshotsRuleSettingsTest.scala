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
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.SnapshotsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.SnapshotName
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

class SnapshotsRuleSettingsTest extends BaseRuleSettingsDecoderTest[SnapshotsRule] {

  "A SnapshotsRule" should {
    "be able to be loaded from settings" when {
      "one snapshot is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: snapshot1
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]] =
              NonEmptySet.one(AlreadyResolved(SnapshotName.from("snapshot1").get.nel))
            rule.settings.allowedSnapshots should be(indices)
          }
        )
      }
      "snapshot is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    snapshots: "snapshot_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedSnapshots.length should be (1)
            rule.settings.allowedSnapshots.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
      "two snapshotes are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: [snapshot1, snapshot2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]] = NonEmptySet.of(
              AlreadyResolved(SnapshotName.from("snapshot1").get.nel),
              AlreadyResolved(SnapshotName.from("snapshot2").get.nel)
            )
            rule.settings.allowedSnapshots should be(indices)
          }
        )
      }
      "two snapshots are defined, second one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    snapshots: [snapshot1, "snapshot_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedSnapshots.length == 2

            rule.settings.allowedSnapshots.head should be(AlreadyResolved(SnapshotName.from("snapshot1").get.nel))
            rule.settings.allowedSnapshots.tail.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "no snapshot is defined" in {
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
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """snapshots: null
                |""".stripMargin)))
          }
        )
      }
      "there is '_all' snapshot defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: [snapshot1, _all]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (snapshots) that matches all the values is redundant - snapshot _all"
            )))
          }
        )
      }
      "there is '*' snapshot defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    snapshots: ["snapshot1", "*", "snapshot2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (snapshots) that matches all the values is redundant - snapshot *"
            )))
          }
        )
      }
    }
  }
}
