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

import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.utils.DurationOps._

import scala.concurrent.duration._
import scala.language.postfixOps

class SessionMaxIdleRuleSettingsTests extends BaseRuleSettingsDecoderTest[SessionMaxIdleRule] {

  "A SessionMaxIdleRule" should {
    "be able to be loaded from config" when {
      "max idle time of session is > 0s" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: "10 s"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.sessionMaxIdle should be((10 seconds).toRefinedPositiveUnsafe)
          }
        )
      }
      "max idle time of session is > 0min" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: 10 min
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.sessionMaxIdle should be((10 minutes).toRefinedPositiveUnsafe)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "session max idle time is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Cannot convert value 'null' to duration")))
          }
        )
      }
      "session max idle time is not in proper format" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: "unknown format"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Cannot convert value '\"unknown format\"' to duration")))
          }
        )
      }
      "session max idle time is < 0" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: -10h
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Only positive values allowed. Found: -10 hours")))
          }
        )
      }
    }
  }
}
