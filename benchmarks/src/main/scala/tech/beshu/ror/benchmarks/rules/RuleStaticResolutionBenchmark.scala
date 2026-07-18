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

import cats.data.{NonEmptyList, NonEmptySet}
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RepositoryRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.RepositoriesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: one `repositories` rule check with a STATICALLY configured allow-list (the common
 * case) — the per-request resolution + matching cost shared with snapshots/data_streams rules.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class RuleStaticResolutionBenchmark {

  @Param(Array("3", "30"))
  var configuredPatterns: Int = scala.compiletime.uninitialized

  private var rule: RepositoriesRule = scala.compiletime.uninitialized
  private var blockContext: RepositoryRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    rule = createRepositoriesRule()
    blockContext = createBlockContext()
    assertRulePermitted(rule.check(blockContext).runSyncUnsafe())
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())

  private def createRepositoriesRule(): RepositoriesRule = {
    val allowed = (0 until configuredPatterns)
      .map(idx => AlreadyResolved(NonEmptyList.one(RepositoryName.from(s"backup-$idx-*").get)): RuntimeMultiResolvableVariable[RepositoryName])
      .toList
    new RepositoriesRule(RepositoriesRule.Settings(NonEmptySet.of(allowed.head, allowed.tail*)))
  }

  private def createBlockContext(): RepositoryRequestBlockContext = {
    val context = new NonIndexRequestContext(realisticHeaders(createCredentials("user1", "pass1")))
    RepositoryRequestBlockContext(
      block = noBlock,
      requestContext = context,
      blockMetadata = BlockMetadata.from(context),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      repositories = Set(RepositoryName.from("backup-0-daily").get)
    )
  }
}
