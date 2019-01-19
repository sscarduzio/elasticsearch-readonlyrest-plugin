package tech.beshu.ror.unit.acl.factory.decoders

import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.refined._

import scala.concurrent.duration._
import scala.language.postfixOps

class SessionMaxIdleRuleSettingsTests extends RuleSettingsDecoderTest[SessionMaxIdleRule] {

  "A SessionMaxIdleRule" should {
    "be able to be loaded from config" when {
      "max idle time of session is > 0s" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: "10 s"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.sessionMaxIdle should be(refineV[Positive](10 seconds).right.get)
          }
        )
      }
      "max idle time of session is > 0min" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: 10 min
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.sessionMaxIdle should be(refineV[Positive](10 minutes).right.get)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "session max idle time is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "session_max_idle" : null
                |}]
              """.stripMargin))))
          }
        )
      }
      "session max idle time is not in proper format" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: "unknown format"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Cannot convert value 'unknown format' to duration")))
          }
        )
      }
      "session max idle time is < 0" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    session_max_idle: -10h
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Only positive durations allowed. Found: -10 hours")))
          }
        )
      }
    }
  }
}
