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

import cats.Eq
import eu.timepit.refined.types.string.NonEmptyString
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.Header

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Quantifies caching the lowercased `Header.Name`: the old Eq lowercased BOTH sides on every
 * comparison (two string allocations per compare, run per header in every findHeader scan and
 * per requirement x header in the header rules); the new Eq compares the precomputed field.
 * Models one scan of 20 request headers for a target name.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class HeaderNameEqBenchmark {

  // What `Header.Name.eqName` was before the lowerCased field was introduced.
  private val oldEq: Eq[Header.Name] = Eq.by(_.value.value.toLowerCase(Locale.US))
  private val newEq: Eq[Header.Name] = Header.Name.eqName

  private var names: Array[Header.Name] = scala.compiletime.uninitialized
  private var target: Header.Name = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    names = (1 to 19).map(i => Header.Name(NonEmptyString.unsafeFrom(s"X-Custom-Header-$i"))).toArray :+
      Header.Name(NonEmptyString.unsafeFrom("authorization"))
    target = Header.Name(NonEmptyString.unsafeFrom("Authorization"))
  }

  private def scan(eq: Eq[Header.Name], bh: Blackhole): Unit = {
    var i = 0
    var found = false
    while (i < names.length) {
      if (eq.eqv(names(i), target)) found = true
      i += 1
    }
    bh.consume(found)
  }

  @Benchmark
  def oldPath_lowercasePerComparison(bh: Blackhole): Unit = scan(oldEq, bh)

  @Benchmark
  def newPath_precomputedLowercase(bh: Blackhole): Unit = scan(newEq, bh)
}
