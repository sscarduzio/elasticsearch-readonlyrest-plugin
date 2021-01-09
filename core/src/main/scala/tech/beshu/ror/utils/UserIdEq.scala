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

import cats.Eq
import tech.beshu.ror.accesscontrol.domain.User

object UserIdEq {
  val caseSensitive: User.Id.UserIdCaseMappingEquality = eqUserWith(CaseMapping.caseSensitiveEquality)
  val caseInsensitive: User.Id.UserIdCaseMappingEquality = eqUserWith(CaseMapping.caseInsensitiveEquality)

  private def eqUserWith(eqString: CaseMapping.StringEquality): User.Id.UserIdCaseMappingEquality = {
    Eq.by[User.Id, String](_.value.value)(eqString)
  }
}
