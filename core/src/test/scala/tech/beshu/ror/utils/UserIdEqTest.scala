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
package tech.beshu.ror.utils

import tech.beshu.ror.accesscontrol.domain.User
import eu.timepit.refined.auto._
import cats.implicits._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.utils.CaseMappingEquality._

class UserIdEqTest
  extends AnyWordSpec {

  import org.scalatest.matchers.should.Matchers._

  private val user1 = User.Id("user1")
  private val User1 = User.Id("User1")
  private val user2 = User.Id("user2")
  "UserIdEq" when {
    "matches case insensitive" should {
      implicit val caseInsensitive: UserIdCaseMappingEquality = UserIdEq.caseInsensitive
      `match`(user1, User1)
      `match`(user1, user1)
      `mismatch`(user1, user2)
    }
    "matches case sensitive" should {
      implicit val caseSensitive: UserIdCaseMappingEquality = UserIdEq.caseSensitive
      `mismatch`(user1, User1)
      `match`(user1, user1)
      `mismatch`(user1, user2)
    }
  }

  private def `match`(user1: User.Id, user2: User.Id)
                     (implicit userIdCaseMappingEquality: UserIdCaseMappingEquality) = {
    show"match $user1, and $user2" in {
      user1 eqv user2 shouldBe true
    }
  }

  private def mismatch(user1: User.Id, user2: User.Id)
                      (implicit userIdCaseMappingEquality: UserIdCaseMappingEquality) = {
    show"mismatch $user1, and $user2" in {
      user1 eqv user2 shouldBe false
    }
  }

}
