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
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable

import java.util.concurrent.TimeUnit
import scala.util.Random

/**
 * Benchmarks the real `GlobPatternsMatcher` (the only `PatternsMatcher` implementation)
 * through its public factory `PatternsMatcher.create`. This is the innermost hot loop of
 * ACL index/action/name matching, and the code path changed by the array+while rewrite.
 *
 * `matchEach*` exercises single-candidate matching against all configured patterns;
 * `filter*` exercises the per-request bulk path. Scenarios cover a small vs large allowed
 * set and case-sensitive vs case-insensitive matching (indices are case-sensitive;
 * user/group matching can be case-insensitive).
 *
 * `@Param` is intentionally avoided (JMH's generated Java accesses param fields directly,
 * which does not mix well with Scala's private-field-plus-accessor encoding); scenarios are
 * enumerated as separate methods instead.
 *
 * Add `-prof gc` (via -PjmhArgs) to see allocation rate (B/op) — the metric most affected
 * by replacing `Vector` + `exists(closure)` with `Array` + `while`.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class GlobPatternsMatcherBenchmark {

  private val fewPatterns = 3
  private val manyPatterns = 30

  private var fewCaseSensitive: PatternsMatcher[String] = scala.compiletime.uninitialized
  private var manyCaseSensitive: PatternsMatcher[String] = scala.compiletime.uninitialized
  private var manyCaseInsensitive: PatternsMatcher[String] = scala.compiletime.uninitialized
  private var candidates: Array[String] = scala.compiletime.uninitialized
  private var candidatesList: List[String] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    def patternsOf(n: Int) = (0 until n).map(i => s"index-$i-*")
    fewCaseSensitive = PatternsMatcher.create[String](patternsOf(fewPatterns))(using Matchable.caseSensitiveStringMatchable)
    manyCaseSensitive = PatternsMatcher.create[String](patternsOf(manyPatterns))(using Matchable.caseSensitiveStringMatchable)
    manyCaseInsensitive = PatternsMatcher.create[String](patternsOf(manyPatterns))(using Matchable.caseInsensitiveStringMatchable)

    // ~30% of candidates match a prefix; ~70% miss and scan all buckets.
    val rnd = new Random(1234)
    candidates = Array.tabulate(2000) { _ =>
      if (rnd.nextInt(100) < 30) s"index-${rnd.nextInt(manyPatterns)}-${rnd.nextInt(1000)}"
      else s"unrelated-${rnd.nextInt(100000)}"
    }
    candidatesList = candidates.toList
  }

  private def matchAll(matcher: PatternsMatcher[String], bh: Blackhole): Unit = {
    var i = 0
    while (i < candidates.length) {
      bh.consume(matcher.`match`(candidates(i)))
      i += 1
    }
  }

  @Benchmark
  def matchEach_fewPatterns_caseSensitive(bh: Blackhole): Unit = matchAll(fewCaseSensitive, bh)

  @Benchmark
  def matchEach_manyPatterns_caseSensitive(bh: Blackhole): Unit = matchAll(manyCaseSensitive, bh)

  @Benchmark
  def matchEach_manyPatterns_caseInsensitive(bh: Blackhole): Unit = matchAll(manyCaseInsensitive, bh)

  @Benchmark
  def filterAll_manyPatterns_caseSensitive(bh: Blackhole): Unit =
    bh.consume(manyCaseSensitive.filter(candidatesList))
}
