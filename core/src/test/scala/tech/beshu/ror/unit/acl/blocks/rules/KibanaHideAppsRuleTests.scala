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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.domain.{Header, KibanaApp, LoggedUser}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.TestsUtils._

class KibanaHideAppsRuleTests extends WordSpec with MockFactory {

  "A KibanaHideAppsRule" should {
    "always match" should {
      "set kibana app header if user is logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1".nonempty))))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id("user1"))))
        (blockContext.withAddedResponseHeader _).expects(headerFrom("x-ror-kibana-hidden-apps" -> "app1")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext) )
      }
      "not set kibana app header if user is not logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1".nonempty))))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }
  }
}
