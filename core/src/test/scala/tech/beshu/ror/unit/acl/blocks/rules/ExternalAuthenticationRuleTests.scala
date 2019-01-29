package tech.beshu.ror.unit.acl.blocks.rules

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.TestsUtils.basicAuthHeader
import tech.beshu.ror.acl.aDomain.{LoggedUser, Secret, User}
import tech.beshu.ror.acl.aDomain.User.Id
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.request.RequestContext

class ExternalAuthenticationRuleTests extends WordSpec with MockFactory {

  "An ExternalAuthenticationRule" should {
    "match" when {
      "external authentication service returns true" in {
        val baHeader = basicAuthHeader("user:pass")
        val externalAuthenticationService = mock[ExternalAuthenticationService]
        (externalAuthenticationService.authenticate _)
          .expects(where { (user: User.Id, secret: Secret) => user.value === "user" && secret.value == "pass" })
          .returning(Task.now(true))

        val requestContext = mock[RequestContext]
        (requestContext.headers _).expects().returning(Set(baHeader))

        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.withLoggedUser _).expects(LoggedUser(Id("user"))).returning(newBlockContext)

        val rule = new ExternalAuthenticationRule(Settings(externalAuthenticationService))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(newBlockContext))
      }
    }
    "not match" when {
      "external authentication service returns false" in {
        val baHeader = basicAuthHeader("user:pass")
        val externalAuthenticationService = mock[ExternalAuthenticationService]
        (externalAuthenticationService.authenticate _)
          .expects(where { (user: User.Id, secret: Secret) => user.value === "user" && secret.value == "pass" })
          .returning(Task.now(false))

        val requestContext = mock[RequestContext]

        (requestContext.headers _).expects().returning(Set(baHeader))
        val blockContext = mock[BlockContext]

        val rule = new ExternalAuthenticationRule(Settings(externalAuthenticationService))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
    }
  }

}
