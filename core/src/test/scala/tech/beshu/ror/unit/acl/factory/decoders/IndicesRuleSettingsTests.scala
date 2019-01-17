package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.IndicesRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.orders._

class IndicesRuleSettingsTests extends RuleSettingsDecoderTest[IndicesRule] with MockFactory {

  "An IndicesRule" should {
    "be able to read from config" when {
      "one index is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: index1
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[Value[IndexName]] = NonEmptySet.one(Const(IndexName("index1")))
            rule.settings.indices should be(indices)
          }
        )
      }
      "index is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: "index_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("index_@{user}"), rv => Right(IndexName(rv.value)))
            rule.settings.indices.length should be (1)
            rule.settings.indices.head shouldBe a [Variable[_]]
            rule.settings.indices.head.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
          }
        )
      }
      "two indexes are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: [index1, index2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[Value[IndexName]] = NonEmptySet.of(Const(IndexName("index1")), Const(IndexName("index2")))
            rule.settings.indices should be(indices)
          }
        )
      }
      "two indexes are defined, second one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices: [index1, "index_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.indices.length == 2

            rule.settings.indices.head should be(Const(IndexName("index1")))

            val variable = Variable(ValueWithVariable("index_@{user}"), rv => Right(IndexName(rv.value)))
            rule.settings.indices.tail.head shouldBe a [Variable[_]]
            rule.settings.indices.tail.head.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no index is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    indices:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "indices" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
