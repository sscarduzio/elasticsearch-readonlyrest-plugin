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

import com.google.common.hash.Hashing

import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

trait Hasher {
  def hashString(input: String): String
}

object Hasher {

  object Sha1 extends Hasher {
    override def hashString(input: String): String = {
      SuppressSha1Deprecation.sha1().hashString(input, Charset.defaultCharset).toString
    }
  }

  object Sha256 extends Hasher {
    override def hashString(input: String): String = {
      Hashing.sha256().hashString(input, Charset.defaultCharset).toString
    }
  }

  object Sha512 extends Hasher {
    override def hashString(input: String): String = {
      Hashing.sha512().hashString(input, Charset.defaultCharset).toString
    }
  }

  object PBKDF2WithHmacSHA512 extends Hasher {
    private val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")

    override def hashString(input: String): String = {
      val spec = new PBEKeySpec(input.toCharArray, input.getBytes, 10000, 64 * 8 /* 512 bits */)
      val key = secretKeyFactory.generateSecret(spec)
      Base64.getEncoder.encodeToString(key.getEncoded)
    }
  }
}
