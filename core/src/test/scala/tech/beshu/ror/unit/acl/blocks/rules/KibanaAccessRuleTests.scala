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
import tech.beshu.ror.Constants.{ADMIN_ACTIONS, CLUSTER_ACTIONS, RO_ACTIONS, RW_ACTIONS}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaAccessRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.{RO, ROStrict, RW, Unrestricted}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.configuration.loader.RorConfigurationIndex
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.{BlockContextAssertion, StringOps}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class KibanaAccessRuleTests extends WordSpec with Inside with BlockContextAssertion {

  "A KibanaAccessRule" when {
    "All and any actions are passed when Unrestricted access" in {
      val anyActions = Set("xyz") ++ asScalaSet(ADMIN_ACTIONS) ++ asScalaSet(RW_ACTIONS) ++ asScalaSet(RO_ACTIONS) ++ asScalaSet(CLUSTER_ACTIONS)
      anyActions.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(Unrestricted), action)()
      }
    }
    "RO action is passed" in {
      RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action)()
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "CLUSTER action is passed" in {
      CLUSTER_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(RO), action)()
        assertMatchRule(settingsOf(RW), action)()
      }
    }
    "RW action is passed" in {
      RW_ACTIONS.asScala.map(str => Action(str.replace("*", "_")))
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName(".kibana".nonempty)))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName(".kibana".nonempty)))
          assertMatchRule(settingsOf(RW), action, Set(IndexName(".kibana".nonempty))) {
            assertBlockContext(
              kibanaIndex = Some(IndexName(".kibana".nonempty)),
              kibanaAccess = Some(RW),
              indices = Set(IndexName(".kibana".nonempty))
            )
          }
        }
    }
    "RO action is passed with other indices" in {
      RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx".nonempty)))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx".nonempty)))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx".nonempty)))()
      }
    }
    "RW action is passed with other indices" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx".nonempty)))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx".nonempty)))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx".nonempty)))
        }
    }
    "RO action is passed with mixed indices" in {
      RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))()
        assertMatchRule(settingsOf(RO), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))()
        assertMatchRule(settingsOf(RW), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))()
      }
    }
    "RW action is passed with mixed indices" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))
          assertNotMatchRule(settingsOf(RO), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))
          assertNotMatchRule(settingsOf(RW), action, Set(IndexName("xxx".nonempty), IndexName(".kibana".nonempty)))
        }
    }
    "RW action is passed with custom kibana index" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict, IndexName(".custom_kibana".nonempty)), action, Set(IndexName(".custom_kibana".nonempty)))
          assertNotMatchRule(settingsOf(RO, IndexName(".custom_kibana".nonempty)), action, Set(IndexName(".custom_kibana".nonempty)))
          assertMatchRule(settingsOf(RW, IndexName(".custom_kibana".nonempty)), action, Set(IndexName(".custom_kibana".nonempty))) {
            assertBlockContext(
              kibanaIndex = Some(IndexName(".custom_kibana".nonempty)),
              kibanaAccess = Some(RW),
              indices = Set(IndexName(".custom_kibana".nonempty)),
            )
          }
        }
    }
    "non strict operations (1)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/index-pattern/job")
      )
    }
    "non strict operations (2)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/delete"),
        uriPath = UriPath("/.custom_kibana/index-pattern/nilb-auh-filebeat-*")
      )
    }
    "non strict operations (3)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:admin/template/put"),
        uriPath = UriPath("/_template/kibana_index_template%3A.kibana")
      )
    }
    "non strict operations (4)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/doc/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b/_update?")
      )
    }
    "non strict operations (5)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/doc/telemetry%3Atelemetry?refresh=wait_for")
      )
    }
    "non strict operations (6)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/doc/url1234/_update?")
      )
    }
    "non strict operations (7)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/url/1234/")
      )
    }
    "non strict operations (8)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/config/1234/_create/something")
      )
    }
    "non strict operations (9)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/_update/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b")
      )
    }
    "non strict operations (10)" in {
      testNonStrictOperations(
        customKibanaIndex = IndexName(".custom_kibana".nonempty),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/_update/url1234")
      )
    }
    "RW can change cluster settings" in {
      assertNotMatchRule(settingsOf(RO, IndexName(".kibana".nonempty)), Action("cluster:admin/settings/update"), Set.empty, Some(UriPath("/_cluster/settings")))
      assertMatchRule(settingsOf(RW, IndexName(".kibana".nonempty)), Action("cluster:admin/settings/update"), Set.empty, Some(UriPath("/_cluster/settings"))) {
        assertBlockContext(
          kibanaIndex = None,
          kibanaAccess = Some(RW)
        )
      }
    }
    "X-Pack cluster settings update" in {
      def assertMatchClusterRule(access: KibanaAccess) = {
        assertMatchRule(settingsOf(access, IndexName(".kibana".nonempty)), Action("cluster:admin/xpack/ccr/auto_follow_pattern/resolve"), Set.empty, Some(UriPath("/_ccr/auto_follow"))) {
          assertBlockContext(
            kibanaIndex = None,
            kibanaAccess = Some(access)
          )
        }
      }

      assertMatchClusterRule(RW)
      assertMatchClusterRule(RO)
    }
  }

  private def testNonStrictOperations(customKibanaIndex: IndexName, action: Action, uriPath: UriPath): Unit = {
    assertNotMatchRule(settingsOf(ROStrict, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath))
    assertMatchRule(settingsOf(RO, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath)) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RO),
        indices = Set(customKibanaIndex)
      )
    }
    assertMatchRule(settingsOf(RW, customKibanaIndex), action, Set(customKibanaIndex), Some(uriPath)) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RW),
        indices = Set(customKibanaIndex)
      )
    }
  }

  private def assertMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, uriPath: Option[UriPath] = None)
                             (blockContextAssertion: BlockContext => Unit = defaultOutputBlockContextAssertion(settings, indices)) =
    assertRule(settings, action, indices, uriPath, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: KibanaAccessRule.Settings, action: Action, indices: Set[IndexName] = Set.empty, uriPath: Option[UriPath] = None) =
    assertRule(settings, action, indices, uriPath, blockContextAssertion = None)

  private def assertRule(settings: KibanaAccessRule.Settings,
                         action: Action,
                         indices: Set[IndexName],
                         uriPath: Option[UriPath] = None,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new KibanaAccessRule(settings)
    val requestContext = MockRequestContext.indices.copy(
      action = action,
      filteredIndices = indices,
      uriPath = uriPath.getOrElse(UriPath(""))
    )
    val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, indices, Set.empty)
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }

  private def settingsOf(access: KibanaAccess, kibanaIndex: IndexName = IndexName(".kibana".nonempty)) = {
    KibanaAccessRule.Settings(access, AlreadyResolved(kibanaIndex), RorConfigurationIndex(IndexName.fromUnsafeString(".readonlyrest")))
  }

  private def defaultOutputBlockContextAssertion(settings: KibanaAccessRule.Settings, indices: Set[IndexName]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        kibanaAccess = Some(settings.access),
        indices = indices
      )(
        blockContext
      )
    }

}
