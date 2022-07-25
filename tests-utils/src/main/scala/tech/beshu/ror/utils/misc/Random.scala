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
package tech.beshu.ror.utils.misc

import java.security.{KeyPairGenerator, PrivateKey, PublicKey, SecureRandom}
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

object Random {

  def generateRsaRandomKeys: (PublicKey, PrivateKey) = {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    val random = SecureRandom.getInstance("SHA1PRNG", "SUN")
    keyGen.initialize(2048, random)
    val pair = keyGen.generateKeyPair()
    (pair.getPublic, pair.getPrivate)
  }

  def instant(origin: Instant, bound: Instant): Instant =
    Instant.ofEpochSecond(
      ThreadLocalRandom.current().nextLong(origin.getEpochSecond, bound.getEpochSecond)
    )

  def instant: Instant =
    instant(Instant.EPOCH, Instant.now())
}
