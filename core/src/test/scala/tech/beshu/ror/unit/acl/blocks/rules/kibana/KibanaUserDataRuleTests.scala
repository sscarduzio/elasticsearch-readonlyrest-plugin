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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.Json.JsonValue.{BooleanValue, NullValue, NumValue, StringValue}
import tech.beshu.ror.accesscontrol.domain.Json.{JsonRepresentation, JsonTree}
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration._
import scala.language.postfixOps
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.ResolvableJsonRepresentationOps._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp

import scala.util.{Failure, Success, Try}

class KibanaUserDataRuleTests
  extends BaseKibanaAccessBasedTests[KibanaUserDataRule, KibanaUserDataRule.Settings] {

  s"A '${RuleName[KibanaUserDataRule].name.value}' rule" when {
    "kibana index template is configured" should {
      "pass the index template to the User Metadata object if the rule matches" in {
        val kibanaTemplateIndex = kibanaIndexName("kibana_template_index")
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = Some(AlreadyResolved(kibanaTemplateIndex)),
          appsToHide = Set.empty,
          allowedApiPaths = Set.empty,
          metadata = None,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withLoggedUser(LoggedUser.DirectlyLoggedUser(User.Id("user1")))
            .withCurrentGroup(GroupName("mygroup"))
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withKibanaTemplateIndex(kibanaTemplateIndex)
        }
      }
    }
    "kibana apps are configured" should {
      "pass the apps to the User Metadata object if the rule matches" in {
        val apps: UniqueNonEmptyList[KibanaApp] = UniqueNonEmptyList.of(FullNameKibanaApp("app1"), FullNameKibanaApp("app2"))
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = None,
          appsToHide = apps.toSet,
          allowedApiPaths = Set.empty,
          metadata = None,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withLoggedUser(LoggedUser.DirectlyLoggedUser(User.Id("user1")))
            .withCurrentGroup(GroupName("mygroup"))
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withHiddenKibanaApps(apps)
        }
      }
    }
    "kibana allowed API paths are configured" should {
      "pass the allowed API paths to the User Metadata object if the rule matches" in {
        val paths: UniqueNonEmptyList[KibanaAllowedApiPath] = UniqueNonEmptyList.of(
          KibanaAllowedApiPath(
            AllowedHttpMethod.Any,
            JavaRegex.buildFromLiteral("/api/index_management/indices")
          ),
          KibanaAllowedApiPath(
            AllowedHttpMethod.Any,
            JavaRegex.compile("""^\/api\/spaces\/.*$""").get
          ),
          KibanaAllowedApiPath(
            AllowedHttpMethod.Specific(HttpMethod.Get),
            JavaRegex.compile("""^\/api\/alerting\/rule\/.*$""").get
          )
        )
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = None,
          appsToHide = Set.empty,
          allowedApiPaths = paths.toSet,
          metadata = None,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withLoggedUser(LoggedUser.DirectlyLoggedUser(User.Id("user1")))
            .withCurrentGroup(GroupName("mygroup"))
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withAllowedKibanaApiPaths(paths)
        }
      }
    }
    "kibana metadata is configured" should {
      "pass the metadata to the User Metadata object if the rule matches" in {
        val metadataJsonRepresentation: JsonRepresentation = {
          JsonTree.Object(Map(
            "a" -> JsonTree.Value(NumValue(1)),
            "b" -> JsonTree.Value(BooleanValue(true)),
            "c" -> JsonTree.Value(StringValue("test")),
            "d" -> JsonTree.Array(
              JsonTree.Value(StringValue("a")) :: JsonTree.Value(StringValue("b")) :: Nil
            ),
            "e" -> JsonTree.Object(Map(
              "f" -> JsonTree.Value(NumValue(1))
            )),
            "g" -> JsonTree.Value(NullValue),
            "h" -> JsonTree.Value(StringValue("@{acl:current_group}_@{acl:user}"))
          ))
        }
        val resolvableMetadataJsonRepresentation = metadataJsonRepresentation.toResolvable(variableCreator) match {
          case Right(value) => value
          case Left(error) => throw new IllegalStateException(s"Cannot resolve metadata: $error")
        }
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = None,
          appsToHide = Set.empty,
          allowedApiPaths = Set.empty,
          metadata = Option(resolvableMetadataJsonRepresentation),
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withLoggedUser(LoggedUser.DirectlyLoggedUser(User.Id("user1")))
            .withCurrentGroup(GroupName("mygroup"))
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withKibanaMetadata(
              JsonTree.Object(Map(
                "a" -> JsonTree.Value(NumValue(1)),
                "b" -> JsonTree.Value(BooleanValue(true)),
                "c" -> JsonTree.Value(StringValue("test")),
                "d" -> JsonTree.Array(
                  JsonTree.Value(StringValue("a")) :: JsonTree.Value(StringValue("b")) :: Nil
                ),
                "e" -> JsonTree.Object(Map(
                  "f" -> JsonTree.Value(NumValue(1))
                )),
                "g" -> JsonTree.Value(NullValue),
                "h" -> JsonTree.Value(StringValue("mygroup_user1"))
              ))
            )
        }
      }
    }
  }

  private def checkRule(rule: KibanaUserDataRule): BlockContext = {
    val requestContext = MockRequestContext.indices.copy(
      headers = Set(currentGroupHeader("mygroup"))
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata
        .from(requestContext)
        .withLoggedUser(LoggedUser.DirectlyLoggedUser(User.Id("user1"))),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty
    )
    val result = Try(rule.check(blockContext).runSyncUnsafe(1 second))
    result match {
      case Success(RuleResult.Fulfilled(blockContext)) =>
        blockContext
      case Success(r@RuleResult.Rejected(_)) =>
        fail(s"Rule was not matched. Result: $r")
      case Failure(exception) =>
        fail(s"Rule was not matched. Exception thrown", exception)
    }
  }

  override protected def createRuleFrom(settings: KibanaUserDataRule.Settings): KibanaUserDataRule =
    new KibanaUserDataRule(settings)

  override protected def settingsOf(access: domain.KibanaAccess,
                                    customKibanaIndex: Option[KibanaIndexName] = None): KibanaUserDataRule.Settings =
    KibanaUserDataRule.Settings(
      access = access,
      kibanaIndex = AlreadyResolved(customKibanaIndex.getOrElse(ClusterIndexName.Local.kibanaDefault)),
      kibanaTemplateIndex = None,
      appsToHide = Set.empty,
      allowedApiPaths = Set.empty,
      metadata = None,
      rorIndex = RorConfigurationIndex(rorIndex)
    )

  override protected def defaultOutputBlockContextAssertion(settings: KibanaUserDataRule.Settings,
                                                            indices: Set[domain.ClusterIndexName],
                                                            dataStreams: Set[DataStreamName],
                                                            customKibanaIndex: Option[KibanaIndexName]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        kibanaAccess = Some(settings.access),
        kibanaIndex = Some(kibanaIndexFrom(customKibanaIndex)),
        indices = indices,
        dataStreams = dataStreams
      )(
        blockContext
      )
    }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
