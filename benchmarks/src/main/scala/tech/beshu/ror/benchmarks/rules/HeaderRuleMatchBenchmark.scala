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

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersAndRule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: one `headers_and` rule check — each configured requirement (name + value glob)
 * scanned against a 20-header request that satisfies all of them, matching headers last.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class HeaderRuleMatchBenchmark {

  @Param(Array("3", "15"))
  var requirements: Int = scala.compiletime.uninitialized

  private var rule: HeadersAndRule = scala.compiletime.uninitialized
  private var blockContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    val required = (0 until requirements)
      .map(i => AccessRequirement.MustBePresent(Header(Header.Name(nes(s"X-Required-$i")), nes(s"allowed-$i-*"))))
      .toList
    rule = new HeadersAndRule(HeadersAndRule.Settings(NonEmptySet.of(required.head, required.tail*)))

    // Filler headers first; the headers satisfying the requirements come last, so scans are full.
    val filler = (0 until math.max(0, 20 - requirements)).map(i => Header(Header.Name(nes(s"X-Filler-$i")), nes(s"value-$i")))
    val matching = (0 until requirements).map(i => Header(Header.Name(nes(s"X-Required-$i")), nes(s"allowed-$i-x")))
    blockContext = new NonIndexRequestContext((filler ++ matching).toCovariantSet).initialBlockContext(null)

    require(
      rule.check(blockContext).runSyncUnsafe().isInstanceOf[Decision.Permitted[?]],
      "expected the headers rule to match"
    )
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())
}
