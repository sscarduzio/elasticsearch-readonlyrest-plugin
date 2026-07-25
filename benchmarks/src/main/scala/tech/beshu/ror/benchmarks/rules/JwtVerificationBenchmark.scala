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
package tech.beshu.ror.benchmarks.rules

import io.jsonwebtoken.{JwtBuilder, Jwts}
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.{AuthenticationJwtDef, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthenticationRule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.json.JsonPath

import java.security.{KeyPair, KeyPairGenerator, SecureRandom}
import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: one `jwt_authentication` rule check — token extraction, signature verification and
 * user-claim lookup — for HMAC-SHA256 and RSA-2048. Sets the floor for a future claims cache.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class JwtVerificationBenchmark {

  private var hmacRule: JwtAuthenticationRule = scala.compiletime.uninitialized
  private var rsaRule: JwtAuthenticationRule = scala.compiletime.uninitialized
  private var hmacContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized
  private var rsaContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    val hmacRawKey = new Array[Byte](32)
    new SecureRandom().nextBytes(hmacRawKey)
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val rsaKeyPair: KeyPair = generator.generateKeyPair()

    hmacRule = createJwtRule(SignatureCheckMethod.Hmac(hmacRawKey))
    rsaRule = createJwtRule(SignatureCheckMethod.Rsa(rsaKeyPair.getPublic))
    hmacContext = createBlockContext(createSignedToken(_.signWith(Keys.hmacShaKeyFor(hmacRawKey))))
    rsaContext = createBlockContext(createSignedToken(_.signWith(rsaKeyPair.getPrivate)))

    assertRulePermitted(hmacRule.check(hmacContext).runSyncUnsafe())
    assertRulePermitted(rsaRule.check(rsaContext).runSyncUnsafe())
  }

  @Benchmark
  def hmac256(bh: Blackhole): Unit =
    bh.consume(hmacRule.check(hmacContext).runSyncUnsafe())

  @Benchmark
  def rsa2048(bh: Blackhole): Unit =
    bh.consume(rsaRule.check(rsaContext).runSyncUnsafe())

  private def createJwtRule(checkMethod: SignatureCheckMethod): JwtAuthenticationRule =
    new JwtAuthenticationRule(
      JwtAuthenticationRule.Settings(AuthenticationJwtDef(
        JwtDef.Name(nes("benchmark-jwt")),
        AuthorizationTokenDef(Header.Name.authorization, StrictlyDefined(AuthorizationTokenPrefix.bearer)),
        checkMethod,
        userClaim = Jwt.ClaimName(JsonPath("sub").get)
      )),
      CaseSensitivity.Enabled
    )

  private def createSignedToken(sign: JwtBuilder => JwtBuilder): String =
    sign(Jwts.builder().subject("user1").claim("groups", "g1,g2")).compact()

  private def createBlockContext(token: String): GeneralNonIndexRequestBlockContext = {
    val headers = (1 to 18).map(idx => Header(Header.Name(nes(s"X-Filler-$idx")), nes(s"value-$idx"))).toCovariantSet +
      Header(Header.Name.authorization, nes(s"Bearer $token"))
    new NonIndexRequestContext(headers).initialBlockContext(noBlock)
  }
}
