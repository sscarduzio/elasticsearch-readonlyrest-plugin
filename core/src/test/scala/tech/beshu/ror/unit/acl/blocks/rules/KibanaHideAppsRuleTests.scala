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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaHideAppsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.domain.KibanaApp
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import eu.timepit.refined.auto._

class KibanaHideAppsRuleTests extends AnyWordSpec with MockFactory {

  "A KibanaHideAppsRule" should {
    "always match" should {
      "set kibana app header if user is logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1"))))
        val requestContext = mock[RequestContext]
        val blockContext = CurrentUserMetadataRequestBlockContext(
          requestContext,
          UserMetadata
            .empty
            .withLoggedUser(DirectlyLoggedUser(Id("user1"))),
          Set.empty,
          List.empty
        )
        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          CurrentUserMetadataRequestBlockContext(
            requestContext,
            UserMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(Id("user1")))
              .withHiddenKibanaApps(NonEmptySet.one(KibanaApp("app1"))),
            Set.empty,
            List.empty
          )
        ))
      }
      "not set kibana app header if user is not logged" in {
        val rule = new KibanaHideAppsRule(KibanaHideAppsRule.Settings(NonEmptySet.of(KibanaApp("app1"))))
        val requestContext = mock[RequestContext]
        val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          CurrentUserMetadataRequestBlockContext(
            requestContext,
            UserMetadata.empty,
            Set.empty,
            List.empty
          )
        ))
      }
    }
  }
}
