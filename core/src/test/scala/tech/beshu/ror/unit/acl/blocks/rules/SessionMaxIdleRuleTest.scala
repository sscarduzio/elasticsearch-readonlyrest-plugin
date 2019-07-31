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
package tech.beshu.ror.unit.acl.blocks.rules

import java.time._
import java.util.UUID

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.TestsUtils.scalaFiniteDuration2JavaDuration
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.domain.{Header, User}
import tech.beshu.ror.acl.refined._
import tech.beshu.ror.providers.UuidProvider
import tech.beshu.ror.unit.acl.blocks.rules.SessionMaxIdleRuleTest.{fixedClock, fixedUuidProvider, rorSessionCookie, someday}

import scala.concurrent.duration._
import scala.language.postfixOps
import tech.beshu.ror.utils.TestsUtils._

class SessionMaxIdleRuleTest extends WordSpec with MockFactory {

  "A SessionMaxIdleRule" should {
    "match" when {
      "user is logged" when {
        implicit val _ = fixedClock
        "ror cookie is not set" in {
          assertRule(
            sessionMaxIdle = positive(10 minutes),
            setRawCookie = rorSessionCookie.forUser1,
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            isMatched = true
          )
        }
        "ror cookie is set, is valid and is not expired" when {
          "only this cookie is present" in {
            assertRule(
              sessionMaxIdle = positive(5 minutes),
              rawCookie = rorSessionCookie.forUser1,
              setRawCookie = rorSessionCookie.forUser1ExpireAfter5Minutes,
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
              isMatched = true
            )
          }
          "there are another cookies" in {
            assertRule(
              sessionMaxIdle = positive(5 minutes),
              rawCookie = s"cookie1=test;${rorSessionCookie.forUser1};last_cookie=123",
              setRawCookie = rorSessionCookie.forUser1ExpireAfter5Minutes,
              loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
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
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Rejected())
      }
      "ror cookie is expired" in {
        implicit val _ = Clock.fixed(someday.toInstant.plus(15 minutes), someday.getZone)
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.forUser1,
          setRawCookie = "",
          loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
          isMatched = false
        )
      }
      "ror cookie of different user" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.forUser1,
          setRawCookie = "",
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2".nonempty))),
          isMatched = false
        )
      }
      "ror cookie signature is wrong" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.wrongSignature,
          setRawCookie = "",
          loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
          isMatched = false
        )
      }
      "ror cookie is malformed" in {
        implicit val _ = fixedClock
        assertRule(
          sessionMaxIdle = positive(5 minutes),
          rawCookie = rorSessionCookie.malformed,
          setRawCookie = "",
          loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
          isMatched = false
        )
      }
    }
  }

  private def assertRule(sessionMaxIdle: FiniteDuration Refined Positive,
                         rawCookie: String = "",
                         setRawCookie: String,
                         loggedUser: Option[DirectlyLoggedUser],
                         isMatched: Boolean)
                        (implicit clock: Clock) = {
    val rule = new SessionMaxIdleRule(Settings(sessionMaxIdle))
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    val newBlockContext = mock[BlockContext]
    val headers = NonEmptyString.unapply(rawCookie) match {
      case Some(cookieHeader) => Set(headerFrom("Cookie" -> cookieHeader.value))
      case None => Set.empty[Header]
    }
    (requestContext.headers _).expects().returning(headers)
    (blockContext.loggedUser _).expects().returning(loggedUser)
    if(isMatched) (blockContext.withAddedResponseHeader _).expects(headerFrom("Set-Cookie" -> setRawCookie)).returning(newBlockContext)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(newBlockContext)
      else Rejected()
    }
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
