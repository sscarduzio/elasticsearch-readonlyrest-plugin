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

import cats.Eq
import eu.timepit.refined.types.string.NonEmptyString
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.Header

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: the production case-insensitive `Header.Name` Eq over a 20-header findHeader-style
 * scan — run per header lookup and per requirement x header in the header rules.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class HeaderNameEqBenchmark {

  private val eq: Eq[Header.Name] = Header.Name.eqName

  private var names: Array[Header.Name] = scala.compiletime.uninitialized
  private var target: Header.Name = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    names = (1 to 19).map(i => Header.Name(NonEmptyString.unsafeFrom(s"X-Custom-Header-$i"))).toArray :+
      Header.Name(NonEmptyString.unsafeFrom("authorization"))
    target = Header.Name(NonEmptyString.unsafeFrom("Authorization"))
  }

  @Benchmark
  def findHeaderScan(bh: Blackhole): Unit = {
    var i = 0
    var found = false
    // Short-circuit like production `find(_.name === name)`; otherwise the cost stays at the full
    // 20 comparisons even if a future maintainer moves the target earlier in the array.
    while (i < names.length && !found) {
      if (eq.eqv(names(i), target)) found = true
      i += 1
    }
    bh.consume(found)
  }
}
