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
package tech.beshu.ror.unit.acl.domain

import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix
import tech.beshu.ror.utils.TestsUtils.*

class AuthorizationTokenTests extends AnyWordSpec with Inside {

  "AuthorizationToken" should {
    "be created from a value with a Bearer prefix" in {
      val token = authorizationTokenFrom("Bearer eyJhbGciOiJIUzI1NiJ9")
      token.prefix should be(AuthorizationTokenPrefix.bearer)
      token.value.value should be("eyJhbGciOiJIUzI1NiJ9")
    }
    "be created from a value with a custom prefix" in {
      val token = authorizationTokenFrom("ApiKey abc123")
      inside(token.prefix) {
        case AuthorizationTokenPrefix.Exact(value) => value.value should be("ApiKey")
      }
      token.value.value should be("abc123")
    }
    "be created from a value with no prefix" in {
      val token = authorizationTokenFrom("myrawtoken")
      token.prefix should be(AuthorizationTokenPrefix.NoPrefix)
      token.value.value should be("myrawtoken")
    }
    "be created from a value with extra whitespace between prefix and token" in {
      val token = authorizationTokenFrom("Bearer  eyJhbGciOiJIUzI1NiJ9")
      token.prefix should be(AuthorizationTokenPrefix.bearer)
      token.value.value should be("eyJhbGciOiJIUzI1NiJ9")
    }
  }

  "AuthorizationTokenPrefix" should {
    "compare Exact prefixes case-insensitively" when {
      "both are lowercase" in {
        val a = AuthorizationTokenPrefix.Exact(nes("bearer"))
        val b = AuthorizationTokenPrefix.Exact(nes("bearer"))
        AuthorizationTokenPrefix.eq.eqv(a, b) should be(true)
      }
      "cases differ" in {
        val lower = AuthorizationTokenPrefix.Exact(nes("bearer"))
        val upper = AuthorizationTokenPrefix.Exact(nes("Bearer"))
        AuthorizationTokenPrefix.eq.eqv(lower, upper) should be(true)
      }
      "all uppercase" in {
        val a = AuthorizationTokenPrefix.Exact(nes("BEARER"))
        val b = AuthorizationTokenPrefix.Exact(nes("bearer"))
        AuthorizationTokenPrefix.eq.eqv(a, b) should be(true)
      }
    }
    "treat different Exact prefixes as not equal" in {
      val bearer = AuthorizationTokenPrefix.Exact(nes("Bearer"))
      val apiKey = AuthorizationTokenPrefix.Exact(nes("ApiKey"))
      AuthorizationTokenPrefix.eq.eqv(bearer, apiKey) should be(false)
    }
    "treat Exact and NoPrefix as not equal" in {
      AuthorizationTokenPrefix.eq.eqv(AuthorizationTokenPrefix.bearer, AuthorizationTokenPrefix.NoPrefix) should be(false)
    }
    "treat NoPrefix as equal to itself" in {
      AuthorizationTokenPrefix.eq.eqv(AuthorizationTokenPrefix.NoPrefix, AuthorizationTokenPrefix.NoPrefix) should be(true)
    }
  }
}
