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

import eu.timepit.refined.types.string.NonEmptyString
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Quantifies making `RequestContext.basicAuth` a lazy val: the old extension def re-ran the
 * header scan + Base64 decode for every basic-auth rule (modeled: 5 auth blocks per request);
 * the new lazy val decodes once and the remaining blocks read the cached value.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class BasicAuthDecodeBenchmark {

  private val authBlocksPerRequest = 5

  private implicit val requestId: RequestId = RequestId("basic-auth-benchmark")

  private var headers: Set[Header] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    def nes(value: String): NonEmptyString = NonEmptyString.unsafeFrom(value)
    headers = (1 to 19).map(i => Header(Header.Name(nes(s"X-Filler-$i")), nes(s"value-$i"))).toCovariantSet +
      BasicAuth.fromCredentials(Credentials(User.Id(nes("admin")), PlainTextSecret(nes("secret-password")))).header
  }

  // The old `basicAuth` extension-def body.
  private def decode(): Option[BasicAuth] =
    headers
      .to(LazyList)
      .map(BasicAuth.fromHeader)
      .find(_.isDefined)
      .flatten

  @Benchmark
  def oldPath_decodePerBlock(bh: Blackhole): Unit = {
    var i = 0
    while (i < authBlocksPerRequest) {
      bh.consume(decode())
      i += 1
    }
  }

  @Benchmark
  def newPath_decodeOnceLazy(bh: Blackhole): Unit = {
    val cached = decode()
    var i = 1
    bh.consume(cached)
    while (i < authBlocksPerRequest) {
      bh.consume(cached)
      i += 1
    }
  }
}
