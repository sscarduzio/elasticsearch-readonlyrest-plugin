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
package tech.beshu.ror.benchmarks

import io.jsonwebtoken.security.Keys
import io.jsonwebtoken.{JwtParser, Jwts}
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.security.{KeyPair, KeyPairGenerator, SecureRandom}
import java.util.concurrent.TimeUnit

/**
 * Quantifies hoisting the jjwt parser to the shared JwtDef/RorKbnDef: the old path rebuilt
 * `Jwts.parser().verifyWith(...).build()` (plus `Keys.hmacShaKeyFor` for HMAC) per request per
 * block; the new path reuses the prebuilt parser and only pays for parse+verify. The newPath
 * numbers also document the per-verification floor that motivates a future claims cache.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class JwtParserBenchmark {

  private var hmacRawKey: Array[Byte] = scala.compiletime.uninitialized
  private var rsaKeyPair: KeyPair = scala.compiletime.uninitialized
  private var hmacToken: String = scala.compiletime.uninitialized
  private var rsaToken: String = scala.compiletime.uninitialized
  private var prebuiltHmacParser: JwtParser = scala.compiletime.uninitialized
  private var prebuiltRsaParser: JwtParser = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    hmacRawKey = new Array[Byte](32)
    new SecureRandom().nextBytes(hmacRawKey)
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    rsaKeyPair = generator.generateKeyPair()

    hmacToken = Jwts.builder().subject("user1").claim("groups", "g1,g2").signWith(Keys.hmacShaKeyFor(hmacRawKey)).compact()
    rsaToken = Jwts.builder().subject("user1").claim("groups", "g1,g2").signWith(rsaKeyPair.getPrivate).compact()

    prebuiltHmacParser = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(hmacRawKey)).build()
    prebuiltRsaParser = Jwts.parser().verifyWith(rsaKeyPair.getPublic).build()
  }

  @Benchmark
  def oldPath_buildParserPerRequest_hmac256(bh: Blackhole): Unit =
    bh.consume(Jwts.parser().verifyWith(Keys.hmacShaKeyFor(hmacRawKey)).build().parseSignedClaims(hmacToken).getPayload)

  @Benchmark
  def newPath_prebuiltParser_hmac256(bh: Blackhole): Unit =
    bh.consume(prebuiltHmacParser.parseSignedClaims(hmacToken).getPayload)

  @Benchmark
  def oldPath_buildParserPerRequest_rsa2048(bh: Blackhole): Unit =
    bh.consume(Jwts.parser().verifyWith(rsaKeyPair.getPublic).build().parseSignedClaims(rsaToken).getPayload)

  @Benchmark
  def newPath_prebuiltParser_rsa2048(bh: Blackhole): Unit =
    bh.consume(prebuiltRsaParser.parseSignedClaims(rsaToken).getPayload)
}
