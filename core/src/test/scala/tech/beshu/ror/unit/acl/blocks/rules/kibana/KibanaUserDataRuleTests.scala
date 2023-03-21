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
import tech.beshu.ror.accesscontrol.domain.IndexName.Kibana
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, KibanaAccess, KibanaAllowedApiPath, KibanaApp, Regex, RorConfigurationIndex}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration._
import scala.language.postfixOps

class KibanaUserDataRuleTests
  extends BaseKibanaAccessBasedTests[KibanaUserDataRule, KibanaUserDataRule.Settings] {

  s"A '${RuleName[KibanaUserDataRule].name.value}' rule" when {
    "kibana index template is configured" should {
      "pass the index template to the User Metadata object in the rule matches" in {
        val kibanaTemplateIndex = localIndexName("kibana_template_index")
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = Some(AlreadyResolved(kibanaTemplateIndex)),
          appsToHide = Set.empty,
          allowedApiPaths = Set.empty,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withKibanaTemplateIndex(kibanaTemplateIndex)
        }
      }
    }
    "kibana apps are configured" should {
      "pass the apps to the User Metadata object in the rule matches" in {
        val apps = UniqueNonEmptyList.of(KibanaApp("app1"), KibanaApp("app2"))
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = None,
          appsToHide = apps.toSet,
          allowedApiPaths = Set.empty,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withHiddenKibanaApps(apps)
        }
      }
    }
    "kibana allowed API paths are configured" should {
      "pass the allowed API paths to the User Metadata object in the rule matches" in {
        val paths: UniqueNonEmptyList[KibanaAllowedApiPath] = UniqueNonEmptyList.of(
          KibanaAllowedApiPath(
            AllowedHttpMethod.Any,
            Regex.buildFromLiteral("/api/index_management/indices")
          ),
          KibanaAllowedApiPath(
            AllowedHttpMethod.Any,
            Regex.compile("""^\/api\/spaces\/.*$""").get
          ),
          KibanaAllowedApiPath(
            AllowedHttpMethod.Specific(HttpMethod.Get),
            Regex.compile("""^\/api\/alerting\/rule\/.*$""").get
          )
        )
        val rule = createRuleFrom(KibanaUserDataRule.Settings(
          access = KibanaAccess.Unrestricted,
          kibanaIndex = AlreadyResolved(ClusterIndexName.Local.kibanaDefault),
          kibanaTemplateIndex = None,
          appsToHide = Set.empty,
          allowedApiPaths = paths.toSet,
          rorIndex = RorConfigurationIndex(rorIndex)
        ))
        val blockContext = checkRule(rule)
        blockContext.userMetadata should be {
          UserMetadata
            .empty
            .withKibanaAccess(KibanaAccess.Unrestricted)
            .withKibanaIndex(ClusterIndexName.Local.kibanaDefault)
            .withAllowedKibanaApiPaths(paths)
        }
      }
    }
  }

  private def checkRule(rule: KibanaUserDataRule): BlockContext = {
    val requestContext = MockRequestContext.indices
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    result match {
      case RuleResult.Fulfilled(blockContext) =>
        blockContext
      case r@RuleResult.Rejected(_) =>
        fail(s"Expected rule was not matched. Result: $r")
    }
  }

  override protected def createRuleFrom(settings: KibanaUserDataRule.Settings): KibanaUserDataRule =
    new KibanaUserDataRule(settings)

  override protected def settingsOf(access: domain.KibanaAccess,
                                    customKibanaIndex: Option[IndexName.Kibana] = None): KibanaUserDataRule.Settings =
    KibanaUserDataRule.Settings(
      access = access,
      kibanaIndex = AlreadyResolved(customKibanaIndex.getOrElse(ClusterIndexName.Local.kibanaDefault)),
      kibanaTemplateIndex = None,
      appsToHide = Set.empty,
      allowedApiPaths = Set.empty,
      rorIndex = RorConfigurationIndex(rorIndex)
    )

  override protected def defaultOutputBlockContextAssertion(settings: KibanaUserDataRule.Settings,
                                                            indices: Set[domain.ClusterIndexName],
                                                            customKibanaIndex: Option[Kibana]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        kibanaAccess = Some(settings.access),
        kibanaIndex = Some(kibanaIndexFrom(customKibanaIndex)),
        indices = indices
      )(
        blockContext
      )
    }
}
