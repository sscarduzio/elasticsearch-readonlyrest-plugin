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
package tech.beshu.ror.accesscontrol.utils

import org.apache.commons.codec.binary.Base64

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import scala.util.Try

object CryptoOps {

  def keyStringToPublicKey(algorithm: String, key: String) = Try {
    val keyBytes = Base64.decodeBase64(key)
    val kf = KeyFactory.getInstance(algorithm)
    kf.generatePublic(new X509EncodedKeySpec(keyBytes))
  }
}
