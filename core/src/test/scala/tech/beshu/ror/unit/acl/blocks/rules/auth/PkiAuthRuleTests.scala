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
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, GroupsAuthorizationFailed}
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.ExtractionSpec
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{PkiAuthRule, PkiAuthenticationRule, PkiAuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class PkiAuthRuleTests
    extends AnyWordSpec
    with BlockContextAssertion
    with WithDummyRequestIdSupport
    with PkiTestSupport {

  "A PkiAuthRule" should {
    "match" when {
      "the certificate identifies the user and carries an allowed group" in {
        assertMatchRule(
          clientCertificate = Some(certificateWith("CN=svc-logstash,OU=ingest,DC=corp")),
          allowedGroups = anyOf("ingest")
        )(
          blockContextAssertion = { blockContext =>
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(User.Id("svc-logstash"))),
              currentGroup = Some(groupIdFrom("ingest")),
              availableGroups = UniqueList.from(List(Group.from(groupIdFrom("ingest"))))
            )
          }
        )
      }
    }
    "not match" when {
      "no certificate was presented, so authentication fails before authorization runs" in {
        assertNotMatchRule(
          clientCertificate = None,
          allowedGroups = anyOf("ingest"),
          denialCause = AuthenticationFailed("No client certificate was presented")
        )
      }
      "the certificate identifies the user but carries no allowed group" in {
        assertNotMatchRule(
          clientCertificate = Some(certificateWith("CN=svc-logstash,OU=query,DC=corp")),
          allowedGroups = anyOf("ingest"),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
    }
  }

  private def assertMatchRule(clientCertificate: Option[ClientCertificate], allowedGroups: GroupsLogic)(
      blockContextAssertion: BlockContext => Unit
  ): Unit =
    assertRule(clientCertificate, allowedGroups, RuleCheckAssertion.RulePermitted(blockContextAssertion))

  private def assertNotMatchRule(
      clientCertificate: Option[ClientCertificate],
      allowedGroups: GroupsLogic,
      denialCause: Cause
  ): Unit =
    assertRule(clientCertificate, allowedGroups, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(
      clientCertificate: Option[ClientCertificate],
      allowedGroups: GroupsLogic,
      assertionType: RuleCheckAssertion
  ): Unit = {
    val rule = ruleWith(allowedGroups)
    val requestContext = requestContextWith(clientCertificate, Set.empty)
    val blockContext = GeneralIndexRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    rule.checkAndAssert(blockContext, assertionType)
  }

  private def ruleWith(allowedGroups: GroupsLogic) = {
    val pkiDef: PkiDef = pki(groupsExtraction = Some(ExtractionSpec.SubjectDnAttribute(nes("OU"))))
    new PkiAuthRule(
      new PkiAuthenticationRule(
        PkiAuthenticationRule.Settings(pkiDef, userIds = None),
        CaseSensitivity.Enabled,
        Impersonation.Disabled
      ),
      new PkiAuthorizationRule(
        PkiAuthorizationRule.Settings(pkiDef, allowedGroups),
        CaseSensitivity.Enabled,
        Impersonation.Disabled
      )
    )
  }

  private def anyOf(groupIds: String*) =
    GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.unsafeFrom(groupIds.toList.map(groupIdFrom))))

  private def groupIdFrom(value: String) = GroupId(NonEmptyString.unsafeFrom(value))

}
