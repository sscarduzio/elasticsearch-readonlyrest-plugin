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
package tech.beshu.ror.unit.utils

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.Hasher

import java.nio.charset.StandardCharsets

class HasherTests extends AnyWordSpec {

  // Reference digests of "abc" from FIPS 180-4. Users keep such hashes in their settings,
  // thus the output format (lowercase hex) must stay the same.
  "A Hasher" should {
    "hash with SHA-1" in {
      Hasher.Sha1.hashString("abc") should be("a9993e364706816aba3e25717850c26c9cd0d89d")
    }
    "hash with SHA-256" in {
      Hasher.Sha256.hashString("abc") should be(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
      )
    }
    "hash with SHA-512" in {
      Hasher.Sha512.hashString("abc") should be(
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
          "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
      )
    }
    "keep the leading zeros of each byte" in {
      Hasher.digestHex("SHA-256", "", StandardCharsets.UTF_8) should be(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
      )
    }
    "hash the bytes of the given charset" in {
      Hasher.digestHex("SHA-256", "zażółć", StandardCharsets.UTF_8) should not be
        Hasher.digestHex("SHA-256", "zażółć", StandardCharsets.ISO_8859_1)
    }
  }

}
