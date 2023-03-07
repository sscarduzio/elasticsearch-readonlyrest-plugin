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
package tech.beshu.ror.unit.acl.blocks.rules.kibana

import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.Constants.{ADMIN_ACTIONS, CLUSTER_ACTIONS, RO_ACTIONS, RW_ACTIONS}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.{RO, ROStrict, RW, Unrestricted}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

trait BaseKibanaAccessBasedTests[RULE <: Rule, SETTINGS]
  extends AnyWordSpec with Inside with BlockContextAssertion {

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
          assertNotMatchRule(settingsOf(ROStrict), action, requestedIndices = Set(clusterIndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, requestedIndices = Set(clusterIndexName(".kibana")))
          assertMatchRule(settingsOf(RW), action, requestedIndices = Set(clusterIndexName(".kibana"))) {
            assertBlockContext(
              kibanaIndex = Some(clusterIndexName(".kibana")),
              kibanaAccess = Some(RW),
              indices = Set(clusterIndexName(".kibana"))
            )
          }
        }
    }
    "RO action is passed with other indices" in {
      RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, requestedIndices = Set(clusterIndexName("xxx")))()
        assertMatchRule(settingsOf(RO), action, requestedIndices = Set(clusterIndexName("xxx")))()
        assertMatchRule(settingsOf(RW), action, requestedIndices = Set(clusterIndexName("xxx")))()
      }
    }
    "RW action is passed with other indices" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, requestedIndices = Set(clusterIndexName("xxx")))
          assertNotMatchRule(settingsOf(RO), action, requestedIndices = Set(clusterIndexName("xxx")))
          assertNotMatchRule(settingsOf(RW), action, requestedIndices = Set(clusterIndexName("xxx")))
        }
    }
    "RO action is passed with mixed indices" in {
      RO_ACTIONS.asScala.map(Action.apply).foreach { action =>
        assertMatchRule(settingsOf(ROStrict), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))()
        assertMatchRule(settingsOf(RO), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))()
        assertMatchRule(settingsOf(RW), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))()
      }
    }
    "RW action is passed with mixed indices" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          assertNotMatchRule(settingsOf(ROStrict), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))
          assertNotMatchRule(settingsOf(RO), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))
          assertNotMatchRule(settingsOf(RW), action, requestedIndices = Set(clusterIndexName("xxx"), clusterIndexName(".kibana")))
        }
    }
    "RW action is passed with custom kibana index" in {
      RW_ACTIONS.asScala.map(Action.apply)
        .foreach { action =>
          val customKibanaIndex = localIndexName(".custom_kibana")
          assertNotMatchRule(
            settingsOf(ROStrict, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(customKibanaIndex)
          )
          assertNotMatchRule(
            settingsOf(RO, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(customKibanaIndex)
          )
          assertMatchRule(
            settingsOf(RW, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(customKibanaIndex)
          ) {
            assertBlockContext(
              kibanaAccess = Some(RW),
              kibanaIndex = Some(customKibanaIndex),
              indices = Set(customKibanaIndex),
            )
          }
        }
    }
    "non strict operations (1)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/index-pattern/job")
      )
    }
    "non strict operations (2)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/delete"),
        uriPath = UriPath("/.custom_kibana/index-pattern/nilb-auh-filebeat-*")
      )
    }
    "non strict operations (3)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:admin/template/put"),
        uriPath = UriPath("/_template/kibana_index_template%3A.kibana")
      )
    }
    "non strict operations (4)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/doc/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b/_update?")
      )
    }
    "non strict operations (5)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/doc/telemetry%3Atelemetry?refresh=wait_for")
      )
    }
    "non strict operations (6)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/doc/url1234/_update?")
      )
    }
    "non strict operations (7)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/url/1234/")
      )
    }
    "non strict operations (8)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/config/1234/_create/something")
      )
    }
    "non strict operations (9)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/_update/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b")
      )
    }
    "non strict operations (10)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath("/.custom_kibana/_update/url1234")
      )
    }
    "non strict operations (11)" in {
      testNonStrictOperations(
        customKibanaIndex = localIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath("/.custom_kibana/_create/url:710d2a92ef849fc282bcb8a216f39046")
      )
    }
    "RW can change cluster settings" in {
      assertNotMatchRule(
        settingsOf(RO),
        Action("cluster:admin/settings/update"),
        requestedIndices = Set.empty,
        uriPath = Some(UriPath("/_cluster/settings"))
      )
      assertMatchRule(
        settingsOf(RW),
        Action("cluster:admin/settings/update"),
        requestedIndices = Set.empty,
        uriPath = Some(UriPath("/_cluster/settings"))
      ) {
        assertBlockContext(
          kibanaIndex = Some(kibanaIndexFrom(None)),
          kibanaAccess = Some(RW)
        )
      }
    }
    "X-Pack cluster settings update" in {
      def assertMatchClusterRule(access: KibanaAccess) = {
        assertMatchRule(
          settingsOf(access),
          Action("cluster:admin/xpack/ccr/auto_follow_pattern/resolve"),
          requestedIndices = Set.empty,
          uriPath = Some(UriPath("/_ccr/auto_follow"))
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexFrom(None)),
            kibanaAccess = Some(access)
          )
        }
      }

      assertMatchClusterRule(RW)
      assertMatchClusterRule(RO)
    }
    "ROR action is used" when {
      "it's current user metadata request action" in {
        assertMatchRule(settingsOf(KibanaAccess.Admin), Action.rorUserMetadataAction, requestedIndices = Set(Local(rorIndex)))()
        assertMatchRule(settingsOf(KibanaAccess.Admin), Action.rorOldConfigAction, requestedIndices = Set(Local(rorIndex)))()
        assertMatchRule(settingsOf(KibanaAccess.Admin), Action.rorConfigAction, requestedIndices = Set(Local(rorIndex)))()
        assertMatchRule(settingsOf(KibanaAccess.Admin), Action.rorAuditEventAction, requestedIndices = Set(Local(rorIndex)))()
      }
    }
  }

  private def testNonStrictOperations(customKibanaIndex: IndexName.Kibana, action: Action, uriPath: UriPath): Unit = {
    assertNotMatchRule(
      settings = settingsOf(ROStrict, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(customKibanaIndex),
      uriPath = Some(uriPath)
    )
    assertMatchRule(
      settings = settingsOf(RO, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(customKibanaIndex),
      uriPath = Some(uriPath)
    ) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RO),
        indices = Set(customKibanaIndex)
      )
    }
    assertMatchRule(
      settings = settingsOf(RW, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(customKibanaIndex),
      uriPath = Some(uriPath)
    ) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RW),
        indices = Set(customKibanaIndex)
      )
    }
  }

  private def assertMatchRule(settings: SETTINGS,
                              action: Action,
                              customKibanaIndex: Option[IndexName.Kibana] = None,
                              requestedIndices: Set[ClusterIndexName] = Set.empty,
                              uriPath: Option[UriPath] = None)
                             (blockContextAssertion: BlockContext => Unit = defaultOutputBlockContextAssertion(settings, requestedIndices, customKibanaIndex)) =
    assertRule(settings, action, customKibanaIndex, requestedIndices, uriPath, Some(blockContextAssertion))

  private def assertNotMatchRule(settings: SETTINGS,
                                 action: Action,
                                 customKibanaIndex: Option[IndexName.Kibana] = None,
                                 requestedIndices: Set[ClusterIndexName] = Set.empty,
                                 uriPath: Option[UriPath] = None) =
    assertRule(settings, action, customKibanaIndex, requestedIndices, uriPath, blockContextAssertion = None)

  private def assertRule(settings: SETTINGS,
                         action: Action,
                         customKibanaIndex: Option[IndexName.Kibana],
                         requestedIndices: Set[ClusterIndexName],
                         uriPath: Option[UriPath] = None,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = createRuleFrom(settings)
    val requestContext = MockRequestContext.indices.copy(
      action = action,
      filteredIndices = requestedIndices,
      uriPath = uriPath.getOrElse(UriPath("/undefined"))
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(customKibanaIndex)),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = requestedIndices,
      allAllowedIndices = Set.empty
    )
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

  protected def createRuleFrom(settings: SETTINGS): RULE

  protected def settingsOf(access: KibanaAccess,
                           customKibanaIndex: Option[IndexName.Kibana] = None): SETTINGS

  protected lazy val rorIndex = fullIndexName(".readonlyrest")

  protected def defaultOutputBlockContextAssertion(settings: SETTINGS,
                                                   indices: Set[ClusterIndexName],
                                                   customKibanaIndex: Option[IndexName.Kibana]): BlockContext => Unit

  protected def kibanaIndexFrom(customKibanaIndex: Option[IndexName.Kibana]) = {
    customKibanaIndex match {
      case Some(index) => index
      case None => Local(fullIndexName(".kibana"))
    }
  }
}
