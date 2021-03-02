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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.RepositoriesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.utils.TestsUtils._

class RepositoriesRuleSettingsTest extends BaseRuleSettingsDecoderTest[RepositoriesRule] {

  "A RepositoriesRule" should {
    "be able to be loaded from config" when {
      "one repository is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    repositories: repository1
              |
              |""".stripMargin,
          assertion = rule => {
            val repositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]] = NonEmptySet.one(AlreadyResolved(RepositoryName("repository1".nonempty).nel))
            rule.settings.allowedRepositories should be(repositories)
          }
        )
      }
      "repository is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    repositories: "repository_@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedRepositories.length should be (1)
            rule.settings.allowedRepositories.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
      "two repositoryes are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    repositories: [repository1, repository2]
              |
              |""".stripMargin,
          assertion = rule => {
            val indices: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]] =
              NonEmptySet.of(AlreadyResolved(RepositoryName("repository1".nonempty).nel), AlreadyResolved(RepositoryName("repository2".nonempty).nel))
            rule.settings.allowedRepositories should be(indices)
          }
        )
      }
      "two repositoryes are defined, second one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    repositories: [repository1, "repository_@{user}"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedRepositories.length == 2

            rule.settings.allowedRepositories.head should be(AlreadyResolved(RepositoryName("repository1".nonempty).nel))
            rule.settings.allowedRepositories.tail.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no repository is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    repositories:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """repositories: null
                |""".stripMargin)))
          }
        )
      }
      "there is '_all' repository defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    repositories: [repository1, _all]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (repositories) that matches all the values is redundant - repository _all"
            )))
          }
        )
      }
      "there is '*' repository defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    repositories: ["repository1", "*", "repository2"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Setting up a rule (repositories) that matches all the values is redundant - repository *"
            )))
          }
        )
      }
    }
  }
}
