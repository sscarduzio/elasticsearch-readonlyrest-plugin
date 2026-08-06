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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{GroupsAuthorizationFailed, ImpersonationNotSupported}
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, Scope}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.PkiAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class PkiAuthorizationRuleTests
    extends AnyWordSpec
    with BlockContextAssertion
    with WithDummyRequestIdSupport
    with PkiTestSupport {

  "A PkiAuthorizationRule" should {
    "match" when {
      "one of the certificate's groups is allowed" in {
        assertMatchRule(
          settings = settingsOf(groupsLogic = anyOf("ingest", "query")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc")))
        )(
          blockContextAssertion = availableGroupsAssertion(User.Id("svc"), "ingest")
        )
      }
      "every required group is on the certificate" in {
        assertMatchRule(
          settings = settingsOf(groupsLogic = allOf("ingest", "metrics")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest,OU=metrics,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc")))
        )(
          blockContextAssertion = availableGroupsAssertion(User.Id("svc"), "ingest", "metrics")
        )
      }
      "the groups are read with a pattern, which finds all of them" in {
        assertMatchRule(
          settings = settingsOf(
            groupsExtraction = ExtractionSpec.SubjectDnPattern(regex("OU=grp-([^,]+)")),
            groupsLogic = anyOf("ingest")
          ),
          clientCertificate = Some(certificateWith("CN=svc,OU=grp-ingest,OU=grp-metrics,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc")))
        )(
          blockContextAssertion = availableGroupsAssertion(User.Id("svc"), "ingest")
        )
      }
    }
    "not match" when {
      "there is no logged user" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = anyOf("ingest")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest")),
          loggedUser = None,
          denialCause = GroupsAuthorizationFailed("No logged user")
        )
      }
      "no client certificate was presented" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = anyOf("ingest")),
          clientCertificate = None,
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc"))),
          denialCause = GroupsAuthorizationFailed("User has no groups")
        )
      }
      "the certificate is out of the scope of the provider" in {
        assertNotMatchRule(
          settings = settingsOf(
            scope = Scope(subjectDnBase = Some(dn("OU=Services,DC=corp")), issuerDn = None),
            groupsLogic = anyOf("ingest")
          ),
          clientCertificate = Some(certificateWith("CN=jsmith,OU=ingest,OU=People,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("jsmith"))),
          denialCause = GroupsAuthorizationFailed("User has no groups")
        )
      }
      "the certificate carries no group at all" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = anyOf("ingest")),
          clientCertificate = Some(certificateWith("CN=svc,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc"))),
          denialCause = GroupsAuthorizationFailed("User has no groups")
        )
      }
      "none of the certificate's groups is allowed" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = anyOf("query")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc"))),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "a required group is missing from the certificate" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = allOf("ingest", "metrics")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest,DC=corp")),
          loggedUser = Some(DirectlyLoggedUser(User.Id("svc"))),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "the user is being impersonated, because groups live on a certificate an impersonator cannot present" in {
        assertNotMatchRule(
          settings = settingsOf(groupsLogic = anyOf("ingest")),
          clientCertificate = Some(certificateWith("CN=svc,OU=ingest,DC=corp")),
          loggedUser = Some(ImpersonatedUser(User.Id("svc"), User.Id("admin"))),
          impersonation = Impersonation.Enabled(
            ImpersonationSettings(impersonators = List.empty, mocksProvider = NoOpMocksProvider)
          ),
          denialCause = ImpersonationNotSupported
        )
      }
    }
  }

  private def settingsOf(
      scope: Scope = Scope.unrestricted,
      groupsExtraction: ExtractionSpec = ExtractionSpec.SubjectDnAttribute(nes("OU")),
      groupsLogic: GroupsLogic
  ) = PkiAuthorizationRule.Settings(
    pki(scope = scope, groupsExtraction = Some(groupsExtraction)),
    groupsLogic
  )

  private def anyOf(groupIds: String*) =
    GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.unsafeFrom(groupIds.toList.map(groupIdFrom))))

  private def allOf(groupIds: String*) =
    GroupsLogic.AllOf(GroupIds(UniqueNonEmptyList.unsafeFrom(groupIds.toList.map(groupIdFrom))))

  private def groupIdFrom(value: String) = GroupId(NonEmptyString.unsafeFrom(value))

  private def assertMatchRule(
      settings: PkiAuthorizationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      loggedUser: Option[LoggedUser],
      impersonation: Impersonation = Impersonation.Disabled
  )(blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(
      settings,
      clientCertificate,
      loggedUser,
      impersonation,
      RuleCheckAssertion.RulePermitted(blockContextAssertion)
    )

  private def assertNotMatchRule(
      settings: PkiAuthorizationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      loggedUser: Option[LoggedUser],
      impersonation: Impersonation = Impersonation.Disabled,
      denialCause: Cause
  ): Unit =
    assertRule(settings, clientCertificate, loggedUser, impersonation, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(
      settings: PkiAuthorizationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      loggedUser: Option[LoggedUser],
      impersonation: Impersonation,
      assertionType: RuleCheckAssertion
  ): Unit = {
    val rule = new PkiAuthorizationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = requestContextWith(clientCertificate, Set.empty)
    val blockContext = GeneralIndexRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = loggedUser match {
        case Some(user) => BlockMetadata.from(requestContext).withLoggedUser(user)
        case None       => BlockMetadata.from(requestContext)
      },
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    rule.checkAndAssert(blockContext, assertionType)
  }

  private def availableGroupsAssertion(user: User.Id, groupIds: String*): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(blockContext)(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = groupIds.headOption.map(groupIdFrom),
        availableGroups = UniqueList.from(groupIds.map(groupIdFrom).map(Group.from))
      )
    }

}
