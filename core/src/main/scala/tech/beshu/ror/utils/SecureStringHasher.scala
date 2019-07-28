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

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.utils.SecureStringHasher.Algorithm

class SecureStringHasher(algorithm: Algorithm) {

  private val underlying = new JavaSecureStringHasher(
    algorithm match {
      case Algorithm.Sha256 => "sha256"
      case Algorithm.Sha348 => "sha348"
      case Algorithm.Sha512 => "sha512"
    }
  )

  def hash(originalKey: NonEmptyString): NonEmptyString = {
    NonEmptyString.unsafeFrom(underlying.hash(originalKey.value))
  }
}

object SecureStringHasher {
  sealed trait Algorithm
  object Algorithm {
    case object Sha256 extends Algorithm
    case object Sha348 extends Algorithm
    case object Sha512 extends Algorithm
  }
}
