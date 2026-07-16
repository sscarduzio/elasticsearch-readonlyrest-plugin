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
package tech.beshu.ror.benchmarks.matchers

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable

import java.util.concurrent.TimeUnit
import scala.util.Random

/**
 * Tier-2 KPI: the real `GlobPatternsMatcher` via `PatternsMatcher.create` — the innermost hot
 * loop of index/action/name matching. 2000 candidates per op, ~30% hit rate.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
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
    fewCaseSensitive = createMatcher(fewPatterns, Matchable.caseSensitiveStringMatchable)
    manyCaseSensitive = createMatcher(manyPatterns, Matchable.caseSensitiveStringMatchable)
    manyCaseInsensitive = createMatcher(manyPatterns, Matchable.caseInsensitiveStringMatchable)
    candidates = createCandidates()
    candidatesList = candidates.toList
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

  private def createMatcher(patternCount: Int, matchable: Matchable[String]): PatternsMatcher[String] =
    PatternsMatcher.create[String]((0 until patternCount).map(idx => s"index-$idx-*"))(using matchable)

  // ~30% of candidates match a prefix; ~70% miss and scan all buckets.
  private def createCandidates(): Array[String] = {
    val rnd = new Random(1234)
    Array.tabulate(2000) { _ =>
      if (rnd.nextInt(100) < 30) s"index-${rnd.nextInt(manyPatterns)}-${rnd.nextInt(1000)}"
      else s"unrelated-${rnd.nextInt(100000)}"
    }
  }

  private def matchAll(matcher: PatternsMatcher[String], bh: Blackhole): Unit =
    candidates.foreach(candidate => bh.consume(matcher.`match`(candidate)))
}
