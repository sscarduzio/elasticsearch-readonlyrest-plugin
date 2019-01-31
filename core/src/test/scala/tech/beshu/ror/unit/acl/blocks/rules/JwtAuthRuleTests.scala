package tech.beshu.ror.unit.acl.blocks.rules

import java.security.{Key, KeyPairGenerator}

import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.{Jwts, SignatureAlgorithm}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils.BlockContextAssertion
import tech.beshu.ror.acl.aDomain.{Group, Header, Secret}
import tech.beshu.ror.acl.blocks.definitions.{ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.acl.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.acl.blocks.rules.JwtAuthRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.mocks.MockRequestContext

import scala.concurrent.duration._
import scala.language.postfixOps

class JwtAuthRuleTests
  extends WordSpec with MockFactory with Inside with BlockContextAssertion[JwtAuthRule.Settings] {

  "A JwtAuthRule" should {
    "match" when {
      "token has valid HS256 signature" in {
        val key: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.Hmac(key.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(key).compact}")
          )
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "token has valid RS256 signature" in {
        val (pub, secret) = {
          val pair = KeyPairGenerator.getInstance("RSA").generateKeyPair()
          (pair.getPublic, pair.getPrivate)
        }
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(secret).compact}")
          )
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "token has no signature and external auth service returns true" in {
        val rawToken = Jwts.builder.setSubject("test").compact
        assertMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.NoCheck(authService(rawToken, authenticated = true)),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $rawToken")
          )
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
    }
    "not match" when {
      "token has invalid HS256 signature" in {
        val key1: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        val key2: Key = Keys.secretKeyFor(SignatureAlgorithm.valueOf("HS256"))
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.Hmac(key1.getEncoded),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(key2).compact}")
          )
        )
      }
      "token has invalid RS256 signature" in {
        val pub = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
        val secret = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.Rsa(pub),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer ${Jwts.builder.setSubject("test").signWith(secret).compact}")
          )
        )
      }
      "token has no signature but external auth service returns false" in {
        val rawToken = Jwts.builder.setSubject("test").compact
        assertNotMatchRule(
          configuredJwtDef = JwtDef(
            JwtDef.Name("test"),
            Header.Name.authorization,
            SignatureCheckMethod.NoCheck(authService(rawToken, authenticated = false)),
            userClaim = None,
            groupsClaim = None
          ),
          tokenHeader = Header(
            Header.Name.authorization,
            NonEmptyString.unsafeFrom(s"Bearer $rawToken")
          )
        )
      }
    }
  }

  private def assertMatchRule(configuredJwtDef: JwtDef,
                              configuredGroups: Set[Group] = Set.empty,
                              tokenHeader: Header)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredJwtDef: JwtDef,
                                 configuredGroups: Set[Group] = Set.empty,
                                 tokenHeader: Header): Unit =
    assertRule(configuredJwtDef, configuredGroups, tokenHeader, blockContextAssertion = None)

  private def assertRule(configuredJwtDef: JwtDef,
                         configuredGroups: Set[Group] = Set.empty,
                         tokenHeader: Header,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new JwtAuthRule(JwtAuthRule.Settings(configuredJwtDef, configuredGroups))
    val requestContext = MockRequestContext(headers = Set(tokenHeader))
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected)
    }
  }

  private def authService(rawToken: String, authenticated: Boolean) = {
    val service = mock[ExternalAuthenticationService]
    (service.authenticate _).expects(*, Secret(rawToken)).returning(Task.now(authenticated))
    service
  }
}
