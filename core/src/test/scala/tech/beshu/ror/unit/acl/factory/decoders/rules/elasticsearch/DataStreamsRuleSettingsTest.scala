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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.DataStreamsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils._

class DataStreamsRuleSettingsTest extends BaseRuleSettingsDecoderTest[DataStreamsRule] {

  "A DataStreamsRule" should {
    "be able to be loaded from config" when {
      "one data stream is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams: data_stream1
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]] =
              NonEmptySet.one(AlreadyResolved(DataStreamName.fromString("data_stream1").get.nel))
            rule.settings.allowedDataStreams should be(indices)
          }
        )
      }
      "data stream is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    data_streams: "data_stream_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedDataStreams.length should be(1)
            rule.settings.allowedDataStreams.head shouldBe a[ToBeResolved[_]]
          }
        )
      }
      "two data streams are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams: [data_stream1, data_stream2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[DataStreamName]] = NonEmptySet.of(
              AlreadyResolved(DataStreamName.fromString("data_stream1").get.nel),
              AlreadyResolved(DataStreamName.fromString("data_stream2").get.nel)
            )
            rule.settings.allowedDataStreams should be(indices)
          }
        )
      }
      "two data streams are defined, second one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    data_streams: [data_stream1, "data_stream_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedDataStreams.length == 2

            rule.settings.allowedDataStreams.head should be(AlreadyResolved(DataStreamName.fromString("data_stream1").get.nel))
            rule.settings.allowedDataStreams.tail.head shouldBe a[ToBeResolved[_]]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no data stream is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """data_streams: null
                |""".stripMargin)))
          }
        )
      }
      "data stream name contains upper case characters" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams: Data_Stream1
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Data stream name cannot contain the upper case characters"
            )))
          }
        )
      }
      "there is '_all' data stream defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams: [data_stream1, _all]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (data_streams) that matches all the values is redundant - data stream *"
            )))
          }
        )
      }
      "there is '*' data stream defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    data_streams: ["data_stream1", "*", "data_stream2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (data_streams) that matches all the values is redundant - data stream *"
            )))
          }
        )
      }
    }
  }
}
