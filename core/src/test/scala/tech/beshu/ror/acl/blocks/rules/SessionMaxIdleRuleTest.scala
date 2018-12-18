package tech.beshu.ror.acl.blocks.rules


import java.time._
import java.util.UUID

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.TestsUtils.scalaFiniteDuration2JavaDuration
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRuleTest._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.UuidProvider
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.{LoggedUser, User}
import tech.beshu.ror.commons.refined._

import scala.concurrent.duration._
import scala.language.postfixOps

class SessionMaxIdleRuleTest extends WordSpec with MockFactory {

  "A SessionMaxIdleRule" should {
    "match" when {
      "user is logged" when {
        implicit val _ = fixedClock
        "ror cookie is not set" in {
          assertRule(
            sessionMaxIdle = positive(10 minutes),
            setRawCookie = rorSessionCookie.forUser1,
            loggedUser = Some(LoggedUser(User.Id("user1"))),
            isMatched = true
          )
        }
        "ror cookie is set, is valid and is not expired" when {
          "only this cookie is present" in {
            assertRule(
              sessionMaxIdle = positive(5 minutes),
              rawCookie = rorSessionCookie.forUser1,
              setRawCookie = rorSessionCookie.forUser1ExpireAfter5Minutes,
              loggedUser = Some(LoggedUser(User.Id("user1"))),
              isMatched = true
            )
          }
          "there are another cookies" in {
            assertRule(
              sessionMaxIdle = positive(5 minutes),
              rawCookie = s"cookie1=test;${rorSessionCookie.forUser1};last_cookie=123",
              setRawCookie = rorSessionCookie.forUser1ExpireAfter5Minutes,
              loggedUser = Some(LoggedUser(User.Id("user1"))),
              isMatched = true
            )
          }
        }
      }
    }
    "not match" when {
      "user is not logged" in {
        implicit val _ = fixedClock
        val rule = new SessionMaxIdleRule(Settings(positive(1 minute)))
        val context = mock[RequestContext]
        (context.loggedUser _).expects().returning(None)
        (context.setResponseHeader _).expects(Header("Set-Cookie" -> ""))
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
      "ror cookie is expired" in {
        implicit val _ = Clock.fixed(someday.toInstant.plus(15 minutes), someday.getZone)
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.forUser1,
          setRawCookie = "",
          loggedUser = Some(LoggedUser(User.Id("user1"))),
          isMatched = false
        )
      }
      "ror cookie of different user" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.forUser1,
          setRawCookie = "",
          loggedUser = Some(LoggedUser(User.Id("user2"))),
          isMatched = false
        )
      }
      "ror cookie signature is wrong" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.wrongSignature,
          setRawCookie = "",
          loggedUser = Some(LoggedUser(User.Id("user1"))),
          isMatched = false
        )
      }
      "ror cookie is malformed" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.malformed,
          setRawCookie = "",
          loggedUser = Some(LoggedUser(User.Id("user1"))),
          isMatched = false
        )
      }
    }
  }

  private def assertRule(sessionMaxIdle: FiniteDuration Refined Positive,
                         rawCookie: String = "",
                         setRawCookie: String,
                         loggedUser: Option[LoggedUser],
                         isMatched: Boolean)
                        (implicit clock: Clock) = {
    val rule = new SessionMaxIdleRule(Settings(sessionMaxIdle))
    val context = mock[RequestContext]
    (context.loggedUser _).expects().returning(loggedUser)
    (context.headers _).expects().returning(Set(Header("Cookie" -> rawCookie)))
    (context.setResponseHeader _).expects(Header("Set-Cookie" -> setRawCookie))
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }

  private def positive(duration: FiniteDuration) = refineV[Positive](duration).right.get

}

object SessionMaxIdleRuleTest {

  private val someday = ZonedDateTime.of(LocalDateTime.of(2012, 12, 12, 0, 0, 0), ZoneId.of("UTC"))
  private val fixedClock: Clock = Clock.fixed(someday.toInstant, someday.getZone)

  implicit val fixedUuidProvider: UuidProvider = new UuidProvider {
    override val instanceUuid: UUID = UUID.fromString("7bcdd01e-4b5a-4dbc-947e-c406e3719ea1")
    override val random: UUID = instanceUuid
  }

  private object rorSessionCookie {

    val forUser1 = // user: user1, expiration date: 2018-12-12 00:10:00; secret: 7bcdd01e-4b5a-4dbc-947e-c406e3719ea1
      """ReadonlyREST_Session=[{"user":"user1","expire":1355271000000},"a439b925eff7d60e55b79dcbffa32c25cf35896c094694f4871e5511c8ff58de"]"""

    val forUser1ExpireAfter5Minutes =
      """ReadonlyREST_Session=[{"user":"user1","expire":1355270700000},"78cc21904914c0d6e3ff37a6146f32717fce90af2c6f2772c8a2af7f3291a3b7"]"""

    val malformed =
      """ReadonlyREST_Session=[{"user":"user1","expiration":1355271000000},"a439b925eff7d60e55b79dcbffa32c25cf35896c094694f4871e5511c8ff58de"]"""

    val wrongSignature =
      """ReadonlyREST_Session=[{"user":"user1","expire":1355271000000},"wrong!!!"]"""
  }
}
