package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.aDomain.User.Id
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.{Header, KibanaApp, LoggedUser}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.TestsUtils._

class KibanaHideAppsRuleTests extends WordSpec with MockFactory {

  "A KibanaHideAppsRule" should {
    "always match" should {
      "set kibana app header if user is logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1"))))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id("user1"))))
        (blockContext.withAddedResponseHeader _).expects(headerFrom("x-ror-kibana-hidden-apps" -> "app1")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext) )
      }
      "not set kibana app header if user is not logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1"))))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }
  }
}
