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

import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AnyOfGroupsRule
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*

import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: per-request cost of one `groups` (any_of) rule check — group-ids resolution,
 * user-definition matching and basic-auth authentication — at field-max scale (100x100).
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class GroupsRuleBenchmark {

  @Param(Array("10", "100"))
  var configuredGroups: Int = scala.compiletime.uninitialized

  @Param(Array("5", "100"))
  var userGroups: Int = scala.compiletime.uninitialized

  private var rule: AnyOfGroupsRule = scala.compiletime.uninitialized
  private var blockContext: GeneralNonIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    rule = createAnyOfGroupsRule("user1", "pass1", configuredGroups, userGroups)
    blockContext = createBlockContext()
    assertRulePermitted(rule.check(blockContext).runSyncUnsafe())
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())

  private def createBlockContext(): GeneralNonIndexRequestBlockContext =
    new NonIndexRequestContext(realisticHeaders(createCredentials("user1", "pass1")))
      .initialBlockContext(noBlock)
}
