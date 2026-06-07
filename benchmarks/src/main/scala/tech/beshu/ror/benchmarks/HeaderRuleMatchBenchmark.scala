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

import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Quantifies the `BaseHeaderRule` optimization: the configured header requirements are static,
 * so their value-matching globs can be compiled once at rule construction instead of building a
 * fresh single-element `PatternsMatcher` for every (requirement x request header) pair on every
 * request (the cost is N_requirements x M_headers matcher builds per request).
 *
 * Both paths use the real `PatternsMatcher` + `Header` production types and reproduce the exact
 * `matches` semantics (case-insensitive header name via `===`, case-sensitive value glob).
 *
 *  - `oldPath_matcherPerComparison` : build `PatternsMatcher.create(value :: Nil)` per comparison
 *    (what `BaseHeaderRule.matches` did before).
 *  - `newPath_precompiledMatcher`   : reuse value matchers compiled once at construction.
 *
 * Models a `headers_and`-style "all must be present" check: every requirement is scanned against
 * every request header. The request includes headers whose NAMES match the requirements (so the
 * value glob is actually evaluated — and, on the old path, actually built — rather than being
 * short-circuited away by the name check) but whose VALUES miss, forcing a full scan. Add
 * `-prof gc` to see the per-request allocation removed.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class HeaderRuleMatchBenchmark {

  private val requestHeaderCount = 20

  private def header(name: String, value: String): Header =
    Header(Header.Name(NonEmptyString.unsafeFrom(name)), NonEmptyString.unsafeFrom(value))

  // Reproduces `BaseHeaderRule.matcherFor`: name `===` + case-sensitive value glob.
  private given Matchable[String] = Matchable.caseSensitiveStringMatchable

  private def requiredHeadersOf(n: Int): Vector[Header] =
    (0 until n).map(i => header(s"X-Required-$i", s"allowed-$i-*")).toVector

  // Request that SATISFIES the rule: a header matching each requirement's name+value glob, so the
  // outer `forall` traverses every requirement and the old path builds one matcher per requirement
  // per request (the cost this optimization removes). Padded with filler headers so each inner
  // `exists` scans a realistic header set. The matching header is placed last so the scan is full.
  private def requestHeadersFor(required: Vector[Header]): Vector[Header] = {
    val filler = (0 until math.max(0, requestHeaderCount - required.size)).map(i => header(s"X-Filler-$i", s"value-$i")).toVector
    // value "allowed-<i>-x" matches the requirement glob "allowed-<i>-*"
    val matching = required.zipWithIndex.map { case (r, i) => header(r.name.value.value, s"allowed-$i-x") }
    filler ++ matching
  }

  private val fewRequired = requiredHeadersOf(3)
  private val manyRequired = requiredHeadersOf(15)

  private val fewPrecompiled: Vector[(Header, PatternsMatcher[String])] =
    fewRequired.map(h => h -> PatternsMatcher.create(h.value.value :: Nil))
  private val manyPrecompiled: Vector[(Header, PatternsMatcher[String])] =
    manyRequired.map(h => h -> PatternsMatcher.create(h.value.value :: Nil))

  private var fewRequestHeaders: Vector[Header] = scala.compiletime.uninitialized
  private var manyRequestHeaders: Vector[Header] = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    fewRequestHeaders = requestHeadersFor(fewRequired)
    manyRequestHeaders = requestHeadersFor(manyRequired)
  }

  // `MustBePresent` semantics (forall requirement: exists header that matches).
  private def oldPath(required: Vector[Header], requestHeaders: Vector[Header]): Boolean =
    required.forall { r =>
      requestHeaders.exists(h => h.name === r.name && PatternsMatcher.create(r.value.value :: Nil).`match`(h.value.value))
    }

  private def newPath(precompiled: Vector[(Header, PatternsMatcher[String])], requestHeaders: Vector[Header]): Boolean =
    precompiled.forall { case (r, valueMatcher) =>
      requestHeaders.exists(h => h.name === r.name && valueMatcher.`match`(h.value.value))
    }

  @Benchmark
  def oldPath_matcherPerComparison_fewRequirements(bh: Blackhole): Unit =
    bh.consume(oldPath(fewRequired, fewRequestHeaders))

  @Benchmark
  def newPath_precompiledMatcher_fewRequirements(bh: Blackhole): Unit =
    bh.consume(newPath(fewPrecompiled, fewRequestHeaders))

  @Benchmark
  def oldPath_matcherPerComparison_manyRequirements(bh: Blackhole): Unit =
    bh.consume(oldPath(manyRequired, manyRequestHeaders))

  @Benchmark
  def newPath_precompiledMatcher_manyRequirements(bh: Blackhole): Unit =
    bh.consume(newPath(manyPrecompiled, manyRequestHeaders))
}
