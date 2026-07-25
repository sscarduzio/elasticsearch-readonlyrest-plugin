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
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: one `actions` rule check over a 10-pattern action list. The miss cost is paid
 * once per block on the deny path, so it multiplies with the ACL block count.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class ActionsRuleBenchmark {

  private var rule: ActionsRule = scala.compiletime.uninitialized
  private var matchingContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized
  private var missingContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    rule = createActionsRule()
    matchingContext = createBlockContext(searchAction)
    missingContext = createBlockContext(Action("cluster:monitor/health"))
    assertRulePermitted(rule.check(matchingContext).runSyncUnsafe())
    assertRuleDenied(rule.check(missingContext).runSyncUnsafe())
  }

  @Benchmark
  def actionMatched(bh: Blackhole): Unit =
    bh.consume(rule.check(matchingContext).runSyncUnsafe())

  @Benchmark
  def actionMissed(bh: Blackhole): Unit =
    bh.consume(rule.check(missingContext).runSyncUnsafe())

  private def createActionsRule(): ActionsRule = {
    val actions = (1 to 9).map(idx => Action(s"indices:admin/custom-$idx/*")).toList :+ searchAction
    new ActionsRule(ActionsRule.Settings(NonEmptySet.of(actions.head, actions.tail*)))
  }

  private def createBlockContext(action: Action): GeneralNonIndexRequestBlockContext =
    new NonIndexRequestContext(realisticHeaders(createCredentials("user1", "pass1")), action)
      .initialBlockContext(noBlock)
}
