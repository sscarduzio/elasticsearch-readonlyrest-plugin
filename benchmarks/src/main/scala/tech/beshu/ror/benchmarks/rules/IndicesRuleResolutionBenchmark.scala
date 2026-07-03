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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: one `indices` rule check on the match path — statically configured glob patterns
 * vs concrete requested indices that all match (read request, empty-cluster ES stub).
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class IndicesRuleResolutionBenchmark {

  @Param(Array("10", "100", "500"))
  var patterns: Int = scala.compiletime.uninitialized

  @Param(Array("1", "50"))
  var requestedIndices: Int = scala.compiletime.uninitialized

  private var rule: IndicesRule = scala.compiletime.uninitialized
  private var blockContext: GeneralIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    val configured = (0 until patterns)
      .map(j => AlreadyResolved(NonEmptyList.one(ClusterIndexName.fromString(s"logs-app-$j-*").get)): RuntimeMultiResolvableVariable[ClusterIndexName])
      .toList
    rule = new IndicesRule(
      IndicesRule.Settings(NonEmptySet.of(configured.head, configured.tail*), mustInvolveIndices = false),
      RandomBasedUniqueIdentifierGenerator
    )

    val requested = (0 until requestedIndices)
      .map(j => RequestedIndex(ClusterIndexName.fromString(s"logs-app-${j % patterns}-day-$j").get, excluded = false))
      .toCovariantSet
    val context = new IndexRequestContext(
      realisticHeaders(Credentials(User.Id(nes("user1")), PlainTextSecret(nes("pass1")))),
      requested
    )
    blockContext = context.initialBlockContext(noBlock)
    require(
      rule.check(blockContext).runSyncUnsafe().isInstanceOf[Decision.Permitted[?]],
      "expected the indices rule to match all requested indices"
    )
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())

  // Wildcard variant: requested `logs-app-*` must EXPAND against the cluster's real index list —
  // the production hot path for wildcard requests (the concrete-name variant above never touches it).
  private var wildcardBlockContext: GeneralIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setupWildcard(): Unit = {
    val clusterIndices = (0 until 200)
      .map { j =>
        new FullLocalIndexWithAliases(
          IndexName.Full(nes(s"logs-app-${j % patterns}-day-$j")),
          IndexAttribute.Opened,
          Set.empty
        )
      }
      .toCovariantSet
    val wildcardRequested =
      Set(RequestedIndex(ClusterIndexName.fromString("logs-app-*").get, excluded = false))
    val context = new IndexRequestContext(
      realisticHeaders(Credentials(User.Id(nes("user1")), PlainTextSecret(nes("pass1")))),
      wildcardRequested,
      esServicesWithIndices(clusterIndices)
    )
    wildcardBlockContext = context.initialBlockContext(noBlock)
    require(
      rule.check(wildcardBlockContext).runSyncUnsafe().isInstanceOf[Decision.Permitted[?]],
      "expected the indices rule to permit the wildcard request after expansion"
    )
  }

  @Benchmark
  def wildcardExpansionPath(bh: Blackhole): Unit =
    bh.consume(rule.check(wildcardBlockContext).runSyncUnsafe())
}
