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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef.ImpersonatedUsers
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.{Id, UserIdPattern}
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, impersonationHeader, unsafeNes}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

abstract class BasicAuthenticationTestTemplate(supportingImpersonation: Boolean)
  extends AnyWordSpec with MockFactory {

  protected def ruleName: String

  protected def ruleCreator: Impersonation => BasicAuthenticationRule[_]

  private lazy val ruleWithoutImpersonation = ruleCreator(Impersonation.Disabled)
  private lazy val ruleWithImpersonation = ruleCreator(Impersonation.Enabled(ImpersonationSettings(
    impersonators = List(
      ImpersonatorDef(
        usernames = UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("admin")))),
        authenticationRule = adminAuthenticationRule(Credentials(User.Id("admin"), PlainTextSecret("admin"))),
        users = ImpersonatedUsers(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("logstash")))))
      ),
      ImpersonatorDef(
        usernames = UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("admin2")))),
        authenticationRule = adminAuthenticationRule(Credentials(User.Id("admin2"), PlainTextSecret("admin2"))),
        users = ImpersonatedUsers(UserIdPatterns(UniqueNonEmptyList.of(UserIdPattern(User.Id("test")))))
      )
    ),
    mocksProvider = NoOpMocksProvider
  )))

  s"An $ruleName" when {
    "impersonation is not configured" should {
      "match" when {
        "basic auth header contains configured in rule's settings value" in {
          val restRequest = mock[RestRequest]
          (() => restRequest.allHeaders).expects().returning(Set(basicAuthHeader("logstash:logstash")))
          val requestContext = mock[RequestContext]
          (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
          (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
          val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
          ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
            GeneralNonIndexRequestBlockContext(
              requestContext = requestContext,
              userMetadata = UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(Id("logstash"))),
              responseHeaders = Set.empty,
              responseTransformations = List.empty
            )
          ))
        }
      }
      "not match" when {
        "basic auth header contains not configured in rule's settings value" in {
          val restRequest = mock[RestRequest]
          (() => restRequest.allHeaders).expects().returning(Set(basicAuthHeader("logstash:nologstash")))
          val requestContext = mock[RequestContext]
          (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
          (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
          val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
          ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
        }
        "basic auth header is absent" in {
          val restRequest = mock[RestRequest]
          (() => restRequest.allHeaders).expects().returning(Set.empty)
          val requestContext = mock[RequestContext]
          (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
          (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
          val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
          ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
        }
      }
    }
    if (supportingImpersonation) {
      "impersonation is configured" when {
        "impersonation header is passed" should {
          "match" when {
            "impersonator can be authenticated" in {
              val restRequest = mock[RestRequest]
              (() => restRequest.allHeaders)
                .expects()
                .returns(Set(basicAuthHeader("admin:admin"), impersonationHeader("logstash")))
                .anyNumberOfTimes()
              val requestContext = mock[RequestContext]
              (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
              (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
              val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
              ruleWithImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
                GeneralNonIndexRequestBlockContext(
                  requestContext = requestContext,
                  userMetadata = UserMetadata.empty.withLoggedUser(ImpersonatedUser(Id("logstash"), Id("admin"))),
                  responseHeaders = Set.empty,
                  responseTransformations = List.empty
                )
              ))
            }
          }
          "not match" when {
            "impersonator cannot be authenticated because of wrong password" in {
              val restRequest = mock[RestRequest]
              (() => restRequest.allHeaders)
                .expects()
                .returns(Set(basicAuthHeader("admin:pass"), impersonationHeader("logstash")))
                .anyNumberOfTimes()
              val requestContext = mock[RequestContext]
              (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
              (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
              val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
              ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
            }
            "there is no such impersonator" in {
              val restRequest = mock[RestRequest]
              (() => restRequest.allHeaders)
                .expects()
                .returns(Set(basicAuthHeader("unknown:admin"), impersonationHeader("logstash")))
                .anyNumberOfTimes()
              val requestContext = mock[RequestContext]
              (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
              (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
              val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
              ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
            }
            "impersonator cannot impersonate the given user" in {
              val restRequest = mock[RestRequest]
              (() => restRequest.allHeaders)
                .expects()
                .returns(Set(basicAuthHeader("admin2:admin2"), impersonationHeader("logstash")))
                .anyNumberOfTimes()
              val requestContext = mock[RequestContext]
              (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
              (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
              val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
              ruleWithoutImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
            }
          }
        }
      }
    } else {
      "impersonation is configured but not supported by the rule" in {
        val restRequest = mock[RestRequest]
        (() => restRequest.allHeaders)
          .expects()
          .returns(Set(basicAuthHeader("admin:admin"), impersonationHeader("logstash")))
          .anyNumberOfTimes()
        val requestContext = mock[RequestContext]
        (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
        (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
        ruleWithImpersonation.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(Cause.ImpersonationNotSupported))
      }
    }
  }

  private def adminAuthenticationRule(credentials: Credentials) = new AuthKeyRule(
    BasicAuthenticationRule.Settings(credentials),
    CaseSensitivity.Enabled,
    Impersonation.Disabled
  )
}