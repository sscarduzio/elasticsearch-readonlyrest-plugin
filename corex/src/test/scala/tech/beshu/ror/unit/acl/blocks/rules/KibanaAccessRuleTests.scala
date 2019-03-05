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


import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.utils.TestsUtils.BlockContextAssertion
import tech.beshu.ror.acl.blocks.rules.KibanaAccessRule
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.domain.KibanaAccess.{RO, ROStrict, RW}
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Const, RequestContextInitiatedBlockContext}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class KibanaAccessRuleTests extends WordSpec with Inside with BlockContextAssertion[KibanaAccessRule.Settings] {

  "A KibanaAccessRule" when {
    "RO action is passed" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action)()
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "CLUSTER action is passed" in {
      Constants.CLUSTER_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "RW action is passed" in {
      Constants.RW_ACTIONS.asScala.map(str => Action(str.replace("*", "_")))
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName(".kibana")))
          assertMatchRule(settingsOf(RW), action, Set(IndexName(".kibana"))) {
            assertBlockContext(
              responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
              kibanaIndex = Some(IndexName(".kibana"))
            )
          }
        }
    }
    "RO action is passed with other indices" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx")))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx")))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx")))()
      }
    }
    "RW action is passed with other indices" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx")))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx")))
        }
    }
    "RO action is passed with mixed indices" in {
      Constants.RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx"), IndexName(".kibana")))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx"), IndexName(".kibana")))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx"), IndexName(".kibana")))()
      }
    }
    "RW action is passed with mixed indices" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx"), IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx"), IndexName(".kibana")))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx"), IndexName(".kibana")))
        }
    }
    "RW action is passed with custom kibana index" in {
      Constants.RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana")))
          assertNotMatchRule(settingsOf(RO, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana")))
          assertMatchRule(settingsOf(RW, IndexName(".custom_kibana")), action, Set(IndexName(".custom_kibana"))) {
            assertBlockContext(
              responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
              kibanaIndex = Some(IndexName(".custom_kibana"))
            )
          }
        }
    }
    "non strict operations (1)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/index-pattern/job")
      )
    }
    "non strict operations (2)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/delete"),
        uriPath = UriPath("/.custom_kibana/index-pattern/nilb-auh-filebeat-*")
      )
    }
    "non strict operations (3)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:admin/template/put"),
        uriPath = UriPath("/_template/kibana_index_template%3A.kibana")
      )
    }
    "non strict operations (4)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/doc/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b/_update?")
      )
    }
  }

  private def testNonStrictOperations(customKibanaIndex: IndexName, action: Action, uriPath: UriPath): Unit = {
    assertNotMatchRule(settingsOf(ROStrict, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath))
    assertMatchRule(settingsOf(RO, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath)){
      assertBlockContext(
        responseHeaders = Set(Header(Name.kibanaAccess, RO: KibanaAccess)),
        kibanaIndex = Some(customKibanaIndex)
      )
    }
    assertMatchRule(settingsOf(RW, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath)) {
      assertBlockContext(
        responseHeaders = Set(Header(Name.kibanaAccess, RW: KibanaAccess)),
        kibanaIndex = Some(customKibanaIndex)
      )
    }
  }

  private def assertMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, uriPath: Option[UriPath] = None)
                             (blockContextAssertion: BlockContext => Unit = defaultOutputBlockContextAssertion(settings)) =
    assertRule(settings, action, indices, uriPath, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, uriPath: Option[UriPath] = None) =
    assertRule(settings, action, indices, uriPath, blockContextAssertion = None)

  private def assertRule(settings: KibanaAccessRule.Settings,
                         action: Action,
                         indices: Set[IndexName],
                         uriPath: Option[UriPath] = None,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new KibanaAccessRule(settings)
    val requestContext = MockRequestContext(
      action = action,
      indices = indices,
      uriPath = uriPath.getOrElse(UriPath(""))
    )
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected)
    }
  }

  private def settingsOf(access: KibanaAccess, kibanaIndex: IndexName = IndexName(".kibana")) = {
    KibanaAccessRule.Settings(access, Const(kibanaIndex), kibanaMetadataEnabled = true)
  }

  private def defaultOutputBlockContextAssertion(settings: KibanaAccessRule.Settings): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(responseHeaders = Set(Header(Name.kibanaAccess, settings.access)))(blockContext)
    }

}
