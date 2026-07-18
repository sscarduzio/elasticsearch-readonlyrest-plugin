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
package tech.beshu.ror.benchmarks.domain

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.createCredentials
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: the production `RequestContext.basicAuth` lookup as paid by 5 basic-auth blocks
 * within one request; a fresh context per op keeps any per-request caching honest.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class BasicAuthDecodeBenchmark {

  private val authBlocksPerRequest = 5

  private var headers: Set[Header] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    headers = realisticHeaders(createCredentials("admin", "secret-password"))
  }

  @Benchmark
  def decodePerRequest(bh: Blackhole): Unit = {
    val context = new NonIndexRequestContext(headers)
    (1 to authBlocksPerRequest).foreach(_ => bh.consume(context.basicAuth))
  }
}
