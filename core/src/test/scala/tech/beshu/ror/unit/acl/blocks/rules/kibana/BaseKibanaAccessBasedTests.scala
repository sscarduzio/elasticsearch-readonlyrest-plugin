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
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{DataStreamRequestBlockContext, GeneralIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.{RO, ROStrict, RW, Unrestricted}
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps

abstract class BaseKibanaAccessBasedTests[RULE <: Rule : RuleName, SETTINGS]
  extends AnyWordSpec with Inside with BlockContextAssertion {

  s"A '${RuleName[RULE].name.value}' rule" when {
    "All and any actions are passed when Unrestricted access" in {
      val anyActions = Set("xyz") ++
        adminActionPatternsMatcher.patterns ++
        rwActionPatternsMatcher.patterns ++
        roActionPatternsMatcher.patterns ++
        clusterActionPatternsMatcher.patterns
      anyActions.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(Unrestricted), action)()
      }
    }
    "RO action is passed" in {
      roActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action)()
      }
    }
    "CLUSTER action is passed" in {
      clusterActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action)()
      }
    }
    "RW action is passed" in {
      rwActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
          assertNotMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set(requestedIndex(".kibana")))
          assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex(".kibana")))
          assertMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex(".kibana"))) {
            assertBlockContext(
              kibanaIndex = Some(kibanaIndexName(".kibana")),
              kibanaAccess = Some(RW),
              indices = Set(requestedIndex(".kibana"))
            )
          }
        }
    }
    "RO action is passed with other indices" in {
      roActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set(requestedIndex("xxx")))()
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx")))()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx")))()
      }
    }
    "RW action is passed with other indices" in {
      rwActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
          assertNotMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set(requestedIndex("xxx")))
          assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx")))
          assertNotMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx")))
        }
    }
    "RO action is passed with mixed indices" in {
      roActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))()
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))()
      }
    }
    "RW action is passed with mixed indices" in {
      rwActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
          assertNotMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))
          assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))
          assertNotMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana")))
        }
    }
    "RW action is passed with custom kibana index" in {
      rwActionPatternsMatcher.patterns.map(Action.apply).foreach { action =>
          val customKibanaIndex = kibanaIndexName(".custom_kibana")
          assertNotMatchRuleUsingIndicesRequest(
            settingsOf(ROStrict, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
          )
          assertNotMatchRuleUsingIndicesRequest(
            settingsOf(RO, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
          )
          assertMatchRuleUsingIndicesRequest(
            settingsOf(RW, Some(customKibanaIndex)),
            action,
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
          ) {
            assertBlockContext(
              kibanaAccess = Some(RW),
              kibanaIndex = Some(customKibanaIndex),
              indices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
            )
          }
        }
    }
    "non strict operations (1)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath.from("/.custom_kibana/index-pattern/job")
      )
    }
    "non strict operations (2)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/delete"),
        uriPath = UriPath.from("/.custom_kibana/index-pattern/nilb-auh-filebeat-*")
      )
    }
    "non strict operations (3)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:admin/template/put"),
        uriPath = UriPath.from("/_template/kibana_index_template%3A.kibana")
      )
    }
    "non strict operations (4)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath.from("/.custom_kibana/doc/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b/_update?")
      )
    }
    "non strict operations (5)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath.from("/.custom_kibana/doc/telemetry%3Atelemetry?refresh=wait_for")
      )
    }
    "non strict operations (6)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath.from("/.custom_kibana/doc/url1234/_update?")
      )
    }
    "non strict operations (7)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath.from("/.custom_kibana/url/1234/")
      )
    }
    "non strict operations (8)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath.from("/.custom_kibana/config/1234/_create/something")
      )
    }
    "non strict operations (9)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath.from("/.custom_kibana/_update/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b")
      )
    }
    "non strict operations (10)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/update"),
        uriPath = UriPath.from("/.custom_kibana/_update/url1234")
      )
    }
    "non strict operations (11)" in {
      testNonStrictOperations(
        customKibanaIndex = kibanaIndexName(".custom_kibana"),
        action = Action("indices:data/write/index"),
        uriPath = UriPath.from("/.custom_kibana/_create/url:710d2a92ef849fc282bcb8a216f39046")
      )
    }
    "RW can change cluster settings" in {
      assertNotMatchRuleUsingIndicesRequest(
        settingsOf(RO),
        Action("cluster:admin/settings/update"),
        requestedIndices = Set.empty,
        uriPath = Some(UriPath.from("/_cluster/settings"))
      )
      assertMatchRuleUsingIndicesRequest(
        settingsOf(RW),
        Action("cluster:admin/settings/update"),
        requestedIndices = Set.empty,
        uriPath = Some(UriPath.from("/_cluster/settings"))
      ) {
        assertBlockContext(
          kibanaIndex = Some(kibanaIndexFrom(None)),
          kibanaAccess = Some(RW)
        )
      }
    }
    "X-Pack cluster settings update" in {
      def assertMatchClusterRule(access: KibanaAccess) = {
        assertMatchRuleUsingIndicesRequest(
          settingsOf(access),
          Action("cluster:admin/xpack/ccr/auto_follow_pattern/resolve"),
          requestedIndices = Set.empty,
          uriPath = Some(UriPath.from("/_ccr/auto_follow"))
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
        assertMatchRuleUsingIndicesRequest(
          settingsOf(KibanaAccess.Admin),
          Action.RorAction.RorUserMetadataAction,
          requestedIndices = Set(RequestedIndex(Local(rorIndex), excluded = false))
        )()
        assertMatchRuleUsingIndicesRequest(
          settingsOf(KibanaAccess.Admin),
          Action.RorAction.RorOldConfigAction,
          requestedIndices = Set(RequestedIndex(Local(rorIndex), excluded = false))
        )()
        assertMatchRuleUsingIndicesRequest(
          settingsOf(KibanaAccess.Admin),
          Action.RorAction.RorConfigAction,
          requestedIndices = Set(RequestedIndex(Local(rorIndex), excluded = false))
        )()
        assertMatchRuleUsingIndicesRequest(
          settingsOf(KibanaAccess.Admin),
          Action.RorAction.RorAuditEventAction,
          requestedIndices = Set(RequestedIndex(Local(rorIndex), excluded = false))
        )()
      }
    }
    "Kibana related index is used" which {
      "is .kibana_8.8.0" in {
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".kibana_8.8.0")),
          uriPath = Some(UriPath.from("/_bulk"))
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana")),
            kibanaAccess = Some(RW),
            indices = Set(requestedIndex(".kibana_8.8.0"))
          )
        }
      }
      "is .kibana_analytics_8.8.0" in {
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".kibana_analytics_8.8.0")),
          uriPath = Some(UriPath.from("/_bulk"))
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana")),
            kibanaAccess = Some(RW),
            indices = Set(requestedIndex(".kibana_analytics_8.8.0"))
          )
        }
      }
      "are .kibana_8.8.0 and .kibana_analytics_8.8.0 at the same time" in {
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".kibana"), requestedIndex(".kibana_analytics_8.8.0")),
          uriPath = Some(UriPath.from("/_bulk"))
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana")),
            kibanaAccess = Some(RW),
            indices = Set(requestedIndex(".kibana"), requestedIndex(".kibana_analytics_8.8.0")),
          )
        }
      }
      "is kibana reporting data stream backing index" in {
        val customKibanaIndex = kibanaIndexName(".kibana-admin")
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW, Some(customKibanaIndex)),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".ds-.kibana-reporting-.kibana-admin-2025.01.01-000001")),
          uriPath = Some(UriPath.from("/_bulk")),
          customKibanaIndex = Some(customKibanaIndex),
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana-admin")),
            kibanaAccess = Some(RW),
            indices = Set(requestedIndex(".ds-.kibana-reporting-.kibana-admin-2025.01.01-000001"))
          )
        }
      }
      "is kibana reporting data stream" in {
        val customKibanaIndex = kibanaIndexName(".kibana-admin")
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW, Some(customKibanaIndex)),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".kibana-reporting-.kibana-admin")),
          uriPath = Some(UriPath.from("/_bulk")),
          customKibanaIndex = Some(customKibanaIndex),
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana-admin")),
            kibanaAccess = Some(RW),
            indices = Set(requestedIndex(".kibana-reporting-.kibana-admin"))
          )
        }
      }
    }
    "Kibana related data stream is used" which {
      "is kibana_sample_data_logs" in {
        assertMatchRuleUsingDataStreamsRequest(
          settingsOf(RW),
          Action("indices:admin/data_stream/create"),
          requestedDataStreams = Set(fullDataStreamName("kibana_sample_data_logs")),
          uriPath = Some(UriPath.from("/_data_stream/kibana_sample_data_logs"))
        ) {
          assertBlockContext(
            kibanaIndex = Some(kibanaIndexName(".kibana")),
            kibanaAccess = Some(RW),
            dataStreams = Set(fullDataStreamName("kibana_sample_data_logs"))
          )
        }
      }
    }
  }

  private def testNonStrictOperations(customKibanaIndex: KibanaIndexName, action: Action, uriPath: UriPath): Unit = {
    assertNotMatchRuleUsingIndicesRequest(
      settings = settingsOf(ROStrict, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false)),
      uriPath = Some(uriPath)
    )
    assertMatchRuleUsingIndicesRequest(
      settings = settingsOf(RO, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false)),
      uriPath = Some(uriPath)
    ) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RO),
        indices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
      )
    }
    assertMatchRuleUsingIndicesRequest(
      settings = settingsOf(RW, Some(customKibanaIndex)),
      action = action,
      customKibanaIndex = Some(customKibanaIndex),
      requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false)),
      uriPath = Some(uriPath)
    ) {
      assertBlockContext(
        kibanaIndex = Some(customKibanaIndex),
        kibanaAccess = Some(RW),
        indices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
      )
    }
  }

  private def assertMatchRuleUsingIndicesRequest(settings: SETTINGS,
                                                 action: Action,
                                                 requestedIndices: Set[RequestedIndex[ClusterIndexName]] = Set.empty,
                                                 customKibanaIndex: Option[KibanaIndexName] = None,
                                                 uriPath: Option[UriPath] = None)
                                                (blockContextAssertion: BlockContext => Unit = defaultOutputBlockContextAssertion(settings, requestedIndices, Set.empty, customKibanaIndex)) =
    assertRuleUsingIndicesRequest(settings, action, customKibanaIndex, requestedIndices, uriPath, Some(blockContextAssertion))

  private def assertMatchRuleUsingDataStreamsRequest(settings: SETTINGS,
                                                     action: Action,
                                                     requestedDataStreams: Set[DataStreamName],
                                                     uriPath: Option[UriPath])
                                                    (blockContextAssertion: BlockContext => Unit) =
    assertRuleUsingDataStreamsRequest(settings, action, None, requestedDataStreams, uriPath, Some(blockContextAssertion))

  private def assertNotMatchRuleUsingIndicesRequest(settings: SETTINGS,
                                                    action: Action,
                                                    customKibanaIndex: Option[KibanaIndexName] = None,
                                                    requestedIndices: Set[RequestedIndex[ClusterIndexName]],
                                                    uriPath: Option[UriPath] = None) =
    assertRuleUsingIndicesRequest(settings, action, customKibanaIndex, requestedIndices, uriPath, blockContextAssertion = None)

  private def assertRuleUsingIndicesRequest(settings: SETTINGS,
                                            action: Action,
                                            customKibanaIndex: Option[KibanaIndexName],
                                            requestedIndices: Set[RequestedIndex[ClusterIndexName]],
                                            uriPath: Option[UriPath],
                                            blockContextAssertion: Option[BlockContext => Unit]) = {
    val requestContext = MockRequestContext.indices.copy(
      restRequest = MockRestRequest(path = uriPath.getOrElse(UriPath.from("/undefined"))),
      action = action,
      filteredIndices = requestedIndices,
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(customKibanaIndex)),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = requestedIndices,
      allAllowedIndices = Set.empty
    )
    assertRule(settings, blockContext, blockContextAssertion)
  }

  private def assertRuleUsingDataStreamsRequest(settings: SETTINGS,
                                                action: Action,
                                                customKibanaIndex: Option[KibanaIndexName],
                                                requestedDataStreams: Set[DataStreamName],
                                                uriPath: Option[UriPath],
                                                blockContextAssertion: Option[BlockContext => Unit]) = {
    val requestContext = MockRequestContext.dataStreams.copy(
      restRequest = MockRestRequest(path = uriPath.getOrElse(UriPath.from("/undefined"))),
      action = action,
      dataStreams = requestedDataStreams,
    )
    val blockContext = DataStreamRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(customKibanaIndex)),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      dataStreams = requestedDataStreams,
      backingIndices = BackingIndices.IndicesNotInvolved
    )
    assertRule(settings, blockContext, blockContextAssertion)
  }

  private def assertRule[B <: BlockContext : BlockContextUpdater](settings: SETTINGS,
                                                                  blockContext: B,
                                                                  blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = createRuleFrom(settings)
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
                           customKibanaIndex: Option[KibanaIndexName] = None): SETTINGS

  protected lazy val rorIndex: IndexName.Full = fullIndexName(".readonlyrest")

  protected def defaultOutputBlockContextAssertion(settings: SETTINGS,
                                                   indices: Set[RequestedIndex[ClusterIndexName]],
                                                   dataStreams: Set[DataStreamName],
                                                   customKibanaIndex: Option[KibanaIndexName]): BlockContext => Unit

  protected def kibanaIndexFrom(customKibanaIndex: Option[KibanaIndexName]): KibanaIndexName = {
    customKibanaIndex match {
      case Some(index) => index
      case None => KibanaIndexName(Local(fullIndexName(".kibana")))
    }
  }
}
