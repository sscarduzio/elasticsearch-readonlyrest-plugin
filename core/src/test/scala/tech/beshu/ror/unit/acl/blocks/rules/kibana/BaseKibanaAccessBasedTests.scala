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

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{
  DataStreamRequestBlockContext,
  GeneralIndexRequestBlockContext,
  TemplateRequestBlockContext
}
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, KibanaPolicy}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.{RO, ROStrict, RW, Unrestricted}
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{AddingIndexTemplate, DeletingIndexTemplates}
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.*
import scala.language.postfixOps

abstract class BaseKibanaAccessBasedTests[RULE <: Rule: RuleName, SETTINGS]
    extends AnyWordSpec
    with Inside
    with BlockContextAssertion {

  s"A '${RuleName[RULE].name.value}' rule" when {
    "All and any actions are passed when Unrestricted access" in {
      val anyActions = Set("xyz", "cluster:admin/settings/update", "cluster:admin/fleet/secrets/get") ++
        readActions.patterns ++
        writeActions.patterns ++
        clusterManagementReadActions.patterns ++
        nonStrictActions.patterns
      anyActions.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(Unrestricted), action)()
      }
    }
    "RO action is passed" in {
      readActions.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action)()
      }
    }
    "cluster management read is passed from rw access level" in {
      clusterManagementReadActions.patterns.map(Action.apply).foreach { action =>
        assertNotMatchRuleUsingIndicesRequest(settingsOf(ROStrict), action, requestedIndices = Set.empty)
        assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set.empty)
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action)()
        assertMatchRuleUsingIndicesRequest(settingsOf(KibanaAccess.Admin), action)()
      }
    }
    "RW action is passed" in {
      writeActions.patterns.map(Action.apply).foreach { action =>
        assertNotMatchRuleUsingIndicesRequest(
          settingsOf(ROStrict),
          action,
          requestedIndices = Set(requestedIndex(".kibana"))
        )
        assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex(".kibana")))
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex(".kibana"))) {
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana")
              )
            ),
            indices = Set(requestedIndex(".kibana"))
          )
        }
      }
    }
    "RO action is passed with other indices" in {
      readActions.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(
          settingsOf(ROStrict),
          action,
          requestedIndices = Set(requestedIndex("xxx"))
        )()
        assertMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx")))()
        assertMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx")))()
      }
    }
    "RW action is passed with other indices" in {
      writeActions.patterns.map(Action.apply).foreach { action =>
        assertNotMatchRuleUsingIndicesRequest(
          settingsOf(ROStrict),
          action,
          requestedIndices = Set(requestedIndex("xxx"))
        )
        assertNotMatchRuleUsingIndicesRequest(settingsOf(RO), action, requestedIndices = Set(requestedIndex("xxx")))
        assertNotMatchRuleUsingIndicesRequest(settingsOf(RW), action, requestedIndices = Set(requestedIndex("xxx")))
      }
    }
    "RO action is passed with mixed indices" in {
      readActions.patterns.map(Action.apply).foreach { action =>
        assertMatchRuleUsingIndicesRequest(
          settingsOf(ROStrict),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )()
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RO),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )()
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )()
      }
    }
    "RW action is passed with mixed indices" in {
      writeActions.patterns.map(Action.apply).foreach { action =>
        assertNotMatchRuleUsingIndicesRequest(
          settingsOf(ROStrict),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )
        assertNotMatchRuleUsingIndicesRequest(
          settingsOf(RO),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )
        assertNotMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          action,
          requestedIndices = Set(requestedIndex("xxx"), requestedIndex(".kibana"))
        )
      }
    }
    "RW action is passed with custom kibana index" in {
      writeActions.patterns.map(Action.apply).foreach { action =>
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = customKibanaIndex
              )
            ),
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
    "legacy template write with the Kibana index template path" in {
      val customKibanaIndex = kibanaIndexName(".custom_kibana")
      List(ROStrict, RO, RW, KibanaAccess.Admin).foreach { access =>
        withClue(s"$access: ") {
          assertNotMatchRuleUsingIndicesRequest(
            settings = settingsOf(access, Some(customKibanaIndex)),
            action = Action("indices:admin/template/put"),
            customKibanaIndex = Some(customKibanaIndex),
            requestedIndices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false)),
            uriPath = Some(UriPath.from("/_template/kibana_index_template%3A.kibana"))
          )
        }
      }
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
    "cluster management write is passed only with unrestricted access" in {
      assertActionsAllowedOnlyFor(
        actions = Set(
          "cluster:admin/settings/update",
          "cluster:admin/ilm/put",
          "cluster:admin/ingest/pipeline/put",
          "cluster:admin/repository/put",
          "cluster:admin/snapshot/create",
          "indices:admin/template/put",
          "indices:admin/index_template/delete",
          "cluster:admin/component_template/put",
          "cluster:admin/xpack/license/delete",
          "cluster:admin/xpack/security/role/put",
          "cluster:admin/xpack/security/api_key/create",
          "cluster:admin/xpack/ml/job/put",
          "cluster:admin/transform/put"
        ),
        allowedFor = Set(Unrestricted)
      )
    }
    "secret value or index data read through a cluster API is passed only with unrestricted access" in {
      assertActionsAllowedOnlyFor(
        actions = Set(
          "cluster:admin/fleet/secrets/get",
          "cluster:admin/xpack/connector/secret/get",
          "cluster:admin/xpack/ml/datafeeds/preview",
          "cluster:admin/transform/preview"
        ),
        allowedFor = Set(Unrestricted)
      )
    }
    "X-Pack action is used" in {
      assertActionsAllowedOnlyFor(
        actions = Set("cluster:monitor/xpack/ml/job/get", "cluster:monitor/xpack/license/get"),
        allowedFor = Set(ROStrict, RO, KibanaAccess.ApiOnly, RW, KibanaAccess.Admin, Unrestricted)
      )
      assertActionsAllowedOnlyFor(
        actions = Set("cluster:admin/xpack/security/role/get", "cluster:admin/xpack/security/api_key/get"),
        allowedFor = Set(RW, KibanaAccess.Admin, Unrestricted)
      )
    }
    "ROR settings API action is used" in {
      assertActionsAllowedOnlyFor(
        actions = Set(
          "cluster:internal_ror/config/manage",
          "cluster:internal_ror/config/refreshsettings",
          "cluster:internal_ror/testconfig/manage",
          "cluster:internal_ror/authmock/manage"
        ),
        allowedFor = Set(KibanaAccess.Admin, Unrestricted)
      )
    }
    "ROR user metadata action is used" in {
      assertActionsAllowedOnlyFor(
        actions = Set("cluster:internal_ror/user_metadata/get"),
        allowedFor = Set(ROStrict, RO, KibanaAccess.ApiOnly, RW, KibanaAccess.Admin, Unrestricted)
      )
    }
    "autoscaling policy get action is used" in {
      assertActionsAllowedOnlyFor(
        actions = Set("cluster:admin/autoscaling/get_autoscaling_policy"),
        allowedFor = Set(RW, KibanaAccess.Admin, Unrestricted)
      )
    }
    "Painless script execution action is used" in {
      assertActionsAllowedOnlyFor(
        actions = Set("cluster:admin/scripts/painless/execute"),
        allowedFor = Set(RW, KibanaAccess.Admin, Unrestricted)
      )
    }
    "Kibana related index is used" which {
      "is .kibana_8.8.0" in {
        assertMatchRuleUsingIndicesRequest(
          settingsOf(RW),
          Action("indices:data/write/bulk"),
          requestedIndices = Set(requestedIndex(".kibana_8.8.0")),
          uriPath = Some(UriPath.from("/_bulk"))
        ) {
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana")
              )
            ),
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana")
              )
            ),
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana")
              )
            ),
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana-admin")
              )
            ),
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana-admin")
              )
            ),
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
          assertBlockContext(_)(
            kibanaPolicy = Some(
              KibanaPolicy.default.copy(
                access = RW,
                index = kibanaIndexName(".kibana")
              )
            ),
            dataStreams = Set(fullDataStreamName("kibana_sample_data_logs"))
          )
        }
      }
    }
    "Kibana sample data set is used" which {
      "is added and removed by the Kibana Sample data page" in {
        val addingAndRemovingLevels = Set[KibanaAccess](RW, KibanaAccess.Admin, Unrestricted)
        List("indices:admin/create", "indices:data/write/bulk", "indices:admin/delete").foreach { action =>
          assertIndicesRequestAllowedOnlyFor(action, "kibana_sample_data_flights", addingAndRemovingLevels)
        }
        List("indices:admin/data_stream/create", "indices:admin/data_stream/delete").foreach { action =>
          assertDataStreamRequestAllowedOnlyFor(action, "kibana_sample_data_logs", addingAndRemovingLevels)
        }
        assertTemplateRequestAllowedOnlyFor(
          "indices:admin/index_template/put",
          AddingIndexTemplate(
            name = TemplateName("kibana_sample_data_logs"),
            patterns = UniqueNonEmptyList.of(indexPattern("kibana_sample_data_logs")),
            aliases = Set.empty
          ),
          addingAndRemovingLevels
        )
        assertTemplateRequestAllowedOnlyFor(
          "indices:admin/index_template/delete",
          DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("kibana_sample_data_logs"))),
          addingAndRemovingLevels
        )
      }
      "is read" in {
        assertIndicesRequestAllowedOnlyFor("indices:data/read/search", "kibana_sample_data_flights", allAccessLevels)
      }
      "is changed with an action that the Kibana Sample data page does not use" in {
        List("indices:data/write/delete/byquery", "indices:admin/close", "indices:admin/settings/update").foreach {
          action => assertIndicesRequestAllowedOnlyFor(action, "kibana_sample_data_logs", Set(Unrestricted))
        }
      }
    }
    "name of a Kibana sample data set is not used in full" which {
      "is an index name with a wildcard" in {
        assertIndicesRequestAllowedOnlyFor("indices:admin/delete", "kibana_sample_data_*", Set(Unrestricted))
      }
      "is the pattern of an index template" in {
        assertTemplateRequestAllowedOnlyFor(
          "indices:admin/index_template/put",
          AddingIndexTemplate(
            name = TemplateName("kibana_sample_data_logs"),
            patterns = UniqueNonEmptyList.of(indexPattern("kibana_sample_data_*")),
            aliases = Set.empty
          ),
          Set(Unrestricted)
        )
      }
      "is the pattern of an index template with another name" in {
        assertTemplateRequestAllowedOnlyFor(
          "indices:admin/index_template/put",
          AddingIndexTemplate(
            name = TemplateName("sample-override"),
            patterns = UniqueNonEmptyList.of(indexPattern("kibana_sample_data_logs")),
            aliases = Set.empty
          ),
          Set(Unrestricted)
        )
      }
    }
  }

  private lazy val allAccessLevels: Set[KibanaAccess] =
    Set(ROStrict, RO, KibanaAccess.ApiOnly, RW, KibanaAccess.Admin, Unrestricted)

  private def assertIndicesRequestAllowedOnlyFor(action: String, index: String, allowedFor: Set[KibanaAccess]): Unit =
    allAccessLevels.foreach { access =>
      withClue(s"$action on $index for $access: ") {
        assertRuleUsingIndicesRequest(
          settingsOf(access),
          Action(action),
          customKibanaIndex = None,
          requestedIndices = Set(requestedIndex(index)),
          uriPath = None,
          blockContextAssertion = permittedIf(allowedFor.contains(access))
        )
      }
    }

  private def assertDataStreamRequestAllowedOnlyFor(
      action: String,
      dataStream: String,
      allowedFor: Set[KibanaAccess]
  ): Unit =
    allAccessLevels.foreach { access =>
      withClue(s"$action on $dataStream for $access: ") {
        assertRuleUsingDataStreamsRequest(
          settingsOf(access),
          Action(action),
          customKibanaIndex = None,
          requestedDataStreams = Set(fullDataStreamName(dataStream)),
          uriPath = None,
          blockContextAssertion = permittedIf(allowedFor.contains(access))
        )
      }
    }

  private def assertTemplateRequestAllowedOnlyFor(
      action: String,
      operation: TemplateOperation,
      allowedFor: Set[KibanaAccess]
  ): Unit =
    allAccessLevels.foreach { access =>
      withClue(s"$action with $operation for $access: ") {
        val requestContext = MockRequestContext.template(operation).copy(action = Action(action))
        val blockContext = TemplateRequestBlockContext(
          block = mock[Block],
          requestContext = requestContext,
          blockMetadata = BlockMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(None)),
          responseHeaders = Set.empty,
          responseTransformations = List.empty,
          templateOperation = operation,
          responseTemplateTransformation = identity,
          allAllowedIndices = Set.empty
        )
        assertRule(settingsOf(access), blockContext, permittedIf(allowedFor.contains(access)))
      }
    }

  // `Some` expects the rule to permit the request, and `None` expects it to deny the request
  private def permittedIf(isAllowed: Boolean): Option[BlockContext => Unit] =
    if (isAllowed) Some(_ => ()) else None

  private def assertActionsAllowedOnlyFor(actions: Set[String], allowedFor: Set[KibanaAccess]): Unit = {
    actions.map(Action.apply).foreach { action =>
      allAccessLevels.foreach { access =>
        withClue(s"${action.value} for $access: ") {
          if (allowedFor.contains(access)) assertMatchRuleUsingIndicesRequest(settingsOf(access), action)()
          else assertNotMatchRuleUsingIndicesRequest(settingsOf(access), action, requestedIndices = Set.empty)
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
      assertBlockContext(_)(
        kibanaPolicy = Some(
          KibanaPolicy.default.copy(
            access = RO,
            index = customKibanaIndex
          )
        ),
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
      assertBlockContext(_)(
        kibanaPolicy = Some(
          KibanaPolicy.default.copy(
            access = RW,
            index = customKibanaIndex
          )
        ),
        indices = Set(RequestedIndex(customKibanaIndex.underlying, excluded = false))
      )
    }
  }

  private def assertMatchRuleUsingIndicesRequest(
      settings: SETTINGS,
      action: Action,
      requestedIndices: Set[RequestedIndex[ClusterIndexName]] = Set.empty,
      customKibanaIndex: Option[KibanaIndexName] = None,
      uriPath: Option[UriPath] = None
  )(
      blockContextAssertion: BlockContext => Unit =
        defaultOutputBlockContextAssertion(settings, requestedIndices, Set.empty, customKibanaIndex)
  ) =
    assertRuleUsingIndicesRequest(
      settings,
      action,
      customKibanaIndex,
      requestedIndices,
      uriPath,
      Some(blockContextAssertion)
    )

  private def assertMatchRuleUsingDataStreamsRequest(
      settings: SETTINGS,
      action: Action,
      requestedDataStreams: Set[DataStreamName],
      uriPath: Option[UriPath]
  )(blockContextAssertion: BlockContext => Unit) =
    assertRuleUsingDataStreamsRequest(
      settings,
      action,
      None,
      requestedDataStreams,
      uriPath,
      Some(blockContextAssertion)
    )

  private def assertNotMatchRuleUsingIndicesRequest(
      settings: SETTINGS,
      action: Action,
      customKibanaIndex: Option[KibanaIndexName] = None,
      requestedIndices: Set[RequestedIndex[ClusterIndexName]],
      uriPath: Option[UriPath] = None
  ) =
    assertRuleUsingIndicesRequest(
      settings,
      action,
      customKibanaIndex,
      requestedIndices,
      uriPath,
      blockContextAssertion = None
    )

  private def assertRuleUsingIndicesRequest(
      settings: SETTINGS,
      action: Action,
      customKibanaIndex: Option[KibanaIndexName],
      requestedIndices: Set[RequestedIndex[ClusterIndexName]],
      uriPath: Option[UriPath],
      blockContextAssertion: Option[BlockContext => Unit]
  ) = {
    val requestContext = MockRequestContext.indices.copy(
      restRequest = MockRestRequest(path = uriPath.getOrElse(UriPath.from("/undefined"))),
      action = action,
      filteredIndices = requestedIndices,
    )
    val blockContext = GeneralIndexRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(customKibanaIndex)),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = requestedIndices,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    assertRule(settings, blockContext, blockContextAssertion)
  }

  private def assertRuleUsingDataStreamsRequest(
      settings: SETTINGS,
      action: Action,
      customKibanaIndex: Option[KibanaIndexName],
      requestedDataStreams: Set[DataStreamName],
      uriPath: Option[UriPath],
      blockContextAssertion: Option[BlockContext => Unit]
  ) = {
    val requestContext = MockRequestContext.dataStreams.copy(
      restRequest = MockRestRequest(path = uriPath.getOrElse(UriPath.from("/undefined"))),
      action = action,
      dataStreams = requestedDataStreams,
    )
    val blockContext = DataStreamRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext).withKibanaIndex(kibanaIndexFrom(customKibanaIndex)),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      dataStreams = requestedDataStreams,
      backingIndices = BackingIndices.IndicesNotInvolved
    )
    assertRule(settings, blockContext, blockContextAssertion)
  }

  private def assertRule[B <: BlockContext: BlockContextUpdater](
      settings: SETTINGS,
      blockContext: B,
      blockContextAssertion: Option[BlockContext => Unit]
  ) = {
    val rule = createRuleFrom(settings)
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Permitted(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Denied(NotAuthorized))
    }
  }

  protected def createRuleFrom(settings: SETTINGS): RULE

  protected def settingsOf(access: KibanaAccess, customKibanaIndex: Option[KibanaIndexName] = None): SETTINGS

  protected lazy val rorIndex: IndexName.Full = fullIndexName(".readonlyrest")

  protected def defaultOutputBlockContextAssertion(
      settings: SETTINGS,
      indices: Set[RequestedIndex[ClusterIndexName]],
      dataStreams: Set[DataStreamName],
      customKibanaIndex: Option[KibanaIndexName]
  ): BlockContext => Unit

  protected def kibanaIndexFrom(customKibanaIndex: Option[KibanaIndexName]): KibanaIndexName = {
    customKibanaIndex match {
      case Some(index) => index
      case None        => KibanaIndexName(Local(fullIndexName(".kibana")))
    }
  }

}
