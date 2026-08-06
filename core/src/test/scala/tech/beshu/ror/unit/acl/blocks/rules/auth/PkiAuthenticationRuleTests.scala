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

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{
  AuthenticationFailed,
  ImpersonationNotAllowed,
  ImpersonationNotSupported
}
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, SanType, Scope}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.PkiAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class PkiAuthenticationRuleTests extends AnyWordSpec with BlockContextAssertion with PkiTestSupport {

  "A PkiAuthenticationRule" should {
    "match" when {
      "the certificate carries a common name and no users are configured" in {
        assertMatchRule(
          settings = settingsOf(pki(), users = None),
          clientCertificate = Some(certificateWith("CN=svc-logstash,OU=Services,DC=corp"))
        )(
          blockContextAssertion = loggedUserAssertion(User.Id("svc-logstash"))
        )
      }
      "the extracted username is on the accepted users list" in {
        assertMatchRule(
          settings = settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("logstash-*")))),
          clientCertificate = Some(certificateWith("CN=logstash-01,OU=Services"))
        )(
          blockContextAssertion = loggedUserAssertion(User.Id("logstash-01"))
        )
      }
      "the certificate is within the scope of the provider" in {
        assertMatchRule(
          settings = settingsOf(
            pki(scope = Scope(subjectDnBase = Some(dn("OU=Services,DC=corp")), issuerDn = None)),
            users = None
          ),
          clientCertificate = Some(certificateWith("CN=svc-logstash,OU=Services,DC=corp"))
        )(
          blockContextAssertion = loggedUserAssertion(User.Id("svc-logstash"))
        )
      }
      "the identity is an Active Directory user principal name" in {
        assertMatchRule(
          settings = settingsOf(
            pki(usersExtraction = ExtractionSpec.San(SanType.Upn, pattern = None)),
            users = None
          ),
          clientCertificate = Some(
            certificateWith(
              "CN=John Smith,OU=People",
              subjectAlternativeNames = List(
                SubjectAlternativeName.OtherName(SubjectAlternativeName.userPrincipalNameOid, "jsmith@corp.example.com")
              )
            )
          )
        )(
          blockContextAssertion = loggedUserAssertion(User.Id("jsmith@corp.example.com"))
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "impersonated user is on the accepted users list" in {
            assertMatchRule(
              settings = settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("userA")))),
              clientCertificate = None,
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = impersonationOf(impersonatedUser = "userA")
            )(
              blockContextAssertion = impersonatedUserAssertion(
                user = User.Id("userA"),
                impersonator = User.Id("admin")
              )
            )
          }
        }
      }
    }
    "not match" when {
      "no client certificate was presented" in {
        assertNotMatchRule(
          settings = settingsOf(pki(), users = None),
          clientCertificate = None,
          denialCause = AuthenticationFailed("No client certificate was presented")
        )
      }
      "the certificate is outside the subject scope of the provider" in {
        assertNotMatchRule(
          settings = settingsOf(
            pki(scope = Scope(subjectDnBase = Some(dn("OU=Services,DC=corp")), issuerDn = None)),
            users = None
          ),
          clientCertificate = Some(certificateWith("CN=John Smith,OU=People,DC=corp")),
          denialCause = AuthenticationFailed("Client certificate is out of the scope of the 'corporate_pki' PKI")
        )
      }
      "the certificate was minted by another issuer" in {
        assertNotMatchRule(
          settings = settingsOf(
            pki(scope = Scope(subjectDnBase = None, issuerDn = Some(dn("CN=Corp Issuing CA,DC=corp")))),
            users = None
          ),
          clientCertificate = Some(certificateWith("CN=svc-logstash", issuer = "CN=Other CA,DC=corp")),
          denialCause = AuthenticationFailed("Client certificate is out of the scope of the 'corporate_pki' PKI")
        )
      }
      "no username can be extracted from the certificate" in {
        assertNotMatchRule(
          settings = settingsOf(pki(), users = None),
          clientCertificate = Some(certificateWith("OU=Services,DC=corp")),
          denialCause = AuthenticationFailed("Cannot extract a username from the client certificate")
        )
      }
      "the extracted username is not on the accepted users list" in {
        assertNotMatchRule(
          settings = settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("logstash-*")))),
          clientCertificate = Some(certificateWith("CN=beats-01,OU=Services")),
          denialCause = AuthenticationFailed("User not found in allowed users list")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            assertNotMatchRule(
              settings = settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("userA")))),
              clientCertificate = None,
              headers = Set(basicAuthHeader("admin:wrong_pass"), impersonationHeader("userA")),
              impersonation = impersonationOf(impersonatedUser = "userA"),
              denialCause = ImpersonationNotAllowed
            )
          }
          "the rule doesn't accept the impersonated user" in {
            assertNotMatchRule(
              settings = settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("userB")))),
              clientCertificate = None,
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = impersonationOf(impersonatedUser = "userA"),
              denialCause = AuthenticationFailed("Impersonated user does not exist")
            )
          }
          "the rule has no accepted users list to check the impersonated user against" in {
            assertNotMatchRule(
              settings = settingsOf(pki(), users = None),
              clientCertificate = None,
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = impersonationOf(impersonatedUser = "userA"),
              denialCause = ImpersonationNotSupported
            )
          }
        }
      }
    }
    "report its local users" when {
      "an accepted users list is configured" in {
        ruleWith(settingsOf(pki(), users = Some(UniqueNonEmptyList.of(User.Id("userA"))))).localUsers should be(
          LocalUsers.Available(AvailableLocalUsers.Known(UniqueNonEmptyList.of(User.Id("userA"))))
        )
      }
      "no accepted users list is configured, so the population is unbounded" in {
        ruleWith(settingsOf(pki(), users = None)).localUsers should be(
          LocalUsers.Available(AvailableLocalUsers.Unknown)
        )
      }
    }
  }

  private def settingsOf(pki: PkiDef, users: Option[UniqueNonEmptyList[User.Id]]) =
    PkiAuthenticationRule.Settings(pki, users)

  private def ruleWith(settings: PkiAuthenticationRule.Settings) =
    new PkiAuthenticationRule(settings, CaseSensitivity.Enabled, Impersonation.Disabled)

  private def assertMatchRule(
      settings: PkiAuthenticationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      headers: Set[Header] = Set.empty,
      impersonation: Impersonation = Impersonation.Disabled
  )(blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(
      settings,
      clientCertificate,
      headers,
      impersonation,
      RuleCheckAssertion.RulePermitted(blockContextAssertion)
    )

  private def assertNotMatchRule(
      settings: PkiAuthenticationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      headers: Set[Header] = Set.empty,
      impersonation: Impersonation = Impersonation.Disabled,
      denialCause: Cause
  ): Unit =
    assertRule(settings, clientCertificate, headers, impersonation, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(
      settings: PkiAuthenticationRule.Settings,
      clientCertificate: Option[ClientCertificate],
      headers: Set[Header],
      impersonation: Impersonation,
      assertionType: RuleCheckAssertion
  ): Unit = {
    val rule = new PkiAuthenticationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = requestContextWith(clientCertificate, headers)
    val blockContext = UserMetadataRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.checkAndAssert(blockContext, assertionType)
  }

  private def impersonationOf(impersonatedUser: String) =
    Impersonation.Enabled(
      ImpersonationSettings(
        impersonators = List(
          impersonatorDefFrom(
            userIdPattern = "*",
            impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
            impersonatedUsersIdPatterns = NonEmptyList.of(impersonatedUser)
          )
        ),
        mocksProvider = NoOpMocksProvider
      )
    )

  private def loggedUserAssertion(user: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => assertBlockContext(blockContext)(loggedUser = Some(DirectlyLoggedUser(user)))

  private def impersonatedUserAssertion(user: User.Id, impersonator: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) =>
      assertBlockContext(blockContext)(loggedUser = Some(ImpersonatedUser(user, impersonator)))

}
