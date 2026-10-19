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

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import java.nio.charset.{Charset, StandardCharsets}
import java.security.MessageDigest
import java.util.Base64

trait Hasher {
  def hashString(input: String, charset: Charset = StandardCharsets.UTF_8): String
}

object Hasher {

  private val hexDigits = "0123456789abcdef".toCharArray

  /** Hashes the input with a JDK digest algorithm and returns the digest as lowercase hex. */
  private def digestHex(algorithm: String, input: String, charset: Charset): String = {
    val digest = MessageDigest.getInstance(algorithm).digest(input.getBytes(charset))
    val hex = new Array[Char](digest.length * 2)
    var i = 0
    while (i < digest.length) {
      hex(2 * i) = hexDigits((digest(i) >> 4) & 0xf)
      hex(2 * i + 1) = hexDigits(digest(i) & 0xf)
      i += 1
    }
    new String(hex)
  }

  object Sha1 extends Hasher {

    override def hashString(input: String, charset: Charset): String = {
      digestHex("SHA-1", input, charset)
    }

  }

  object Sha256 extends Hasher {

    override def hashString(input: String, charset: Charset): String = {
      digestHex("SHA-256", input, charset)
    }

  }

  object Sha512 extends Hasher {

    override def hashString(input: String, charset: Charset): String = {
      digestHex("SHA-512", input, charset)
    }

  }

  object PBKDF2WithHmacSHA512 extends Hasher {
    private val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")

    override def hashString(input: String, charset: Charset): String = {
      val spec = new PBEKeySpec(input.toCharArray, input.getBytes(charset), 10000, 64 * 8 /* 512 bits */ )
      val key = secretKeyFactory.generateSecret(spec)
      Base64.getEncoder.encodeToString(key.getEncoded)
    }

  }

}
