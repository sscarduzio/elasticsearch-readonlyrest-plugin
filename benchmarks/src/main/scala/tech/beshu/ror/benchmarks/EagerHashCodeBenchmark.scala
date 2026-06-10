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

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Measures what EagerHashCode actually buys on `RequestedIndex`: the production class (cached
 * hash) vs an identically shaped plain case class (hash recomputed per use). `lookups_*` reuses
 * instances across Set.contains calls (the hot-path claim); `createAndLookup_*` includes the
 * construction so the eager-at-creation cost is also visible. Both inner names are cached
 * either way, so the delta isolates exactly the round-1 commit.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class EagerHashCodeBenchmark {

  // Same shape as RequestedIndex, without the EagerHashCode mixin.
  private final case class PlainRequestedIndex(name: ClusterIndexName, excluded: Boolean)

  private var cachedSet: Set[RequestedIndex[ClusterIndexName]] = scala.compiletime.uninitialized
  private var plainSet: Set[PlainRequestedIndex] = scala.compiletime.uninitialized
  private var cachedQueries: Array[RequestedIndex[ClusterIndexName]] = scala.compiletime.uninitialized
  private var plainQueries: Array[PlainRequestedIndex] = scala.compiletime.uninitialized
  private var queryNames: Array[ClusterIndexName] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    def name(i: Int): ClusterIndexName = ClusterIndexName.fromString(s"index-$i").get

    val memberNames = (0 until 30).map(name)
    cachedSet = memberNames.map(RequestedIndex(_, excluded = false)).toCovariantSet
    plainSet = memberNames.map(PlainRequestedIndex(_, excluded = false)).toCovariantSet

    // ~50% hits; instances are created once and reused per op, like per-request objects flowing through several Sets.
    queryNames = (0 until 100).map(i => name(if (i % 2 == 0) i % 30 else 1000 + i)).toArray
    cachedQueries = queryNames.map(RequestedIndex(_, excluded = false))
    plainQueries = queryNames.map(PlainRequestedIndex(_, excluded = false))
  }

  @Benchmark
  def lookups_eagerHashCode(bh: Blackhole): Unit = {
    var i = 0
    while (i < cachedQueries.length) {
      bh.consume(cachedSet.contains(cachedQueries(i)))
      i += 1
    }
  }

  @Benchmark
  def lookups_plainCaseClass(bh: Blackhole): Unit = {
    var i = 0
    while (i < plainQueries.length) {
      bh.consume(plainSet.contains(plainQueries(i)))
      i += 1
    }
  }

  @Benchmark
  def createAndLookup_eagerHashCode(bh: Blackhole): Unit = {
    var i = 0
    while (i < queryNames.length) {
      bh.consume(cachedSet.contains(RequestedIndex(queryNames(i), excluded = false)))
      i += 1
    }
  }

  @Benchmark
  def createAndLookup_plainCaseClass(bh: Blackhole): Unit = {
    var i = 0
    while (i < queryNames.length) {
      bh.consume(plainSet.contains(PlainRequestedIndex(queryNames(i), excluded = false)))
      i += 1
    }
  }
}
