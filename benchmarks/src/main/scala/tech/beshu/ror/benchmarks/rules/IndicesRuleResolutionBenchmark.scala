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
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.benchmarks.support.BenchmarkAclUtils.*
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-1 KPI: one `indices` rule check. `matchPath` = statically configured glob patterns vs
 * concrete requested indices that all match. `wildcardExpansionPath` = a requested `logs-app-*`
 * that must expand against the cluster's index list (the production hot path for wildcard requests).
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
  private var wildcardBlockContext: GeneralIndexRequestBlockContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    rule = createIndicesRule()
    blockContext = createConcreteNamesBlockContext()
    wildcardBlockContext = createWildcardBlockContext()
    assertRulePermitted(rule.check(blockContext).runSyncUnsafe())
    assertRulePermitted(rule.check(wildcardBlockContext).runSyncUnsafe())
  }

  @Benchmark
  def matchPath(bh: Blackhole): Unit =
    bh.consume(rule.check(blockContext).runSyncUnsafe())

  @Benchmark
  def wildcardExpansionPath(bh: Blackhole): Unit =
    bh.consume(rule.check(wildcardBlockContext).runSyncUnsafe())

  private def createIndicesRule(): IndicesRule = {
    val configured = (0 until patterns)
      .map(idx => AlreadyResolved(NonEmptyList.one(ClusterIndexName.fromString(s"logs-app-$idx-*").get)): RuntimeMultiResolvableVariable[ClusterIndexName])
      .toList
    new IndicesRule(
      IndicesRule.Settings(NonEmptySet.of(configured.head, configured.tail*), mustInvolveIndices = false),
      RandomBasedUniqueIdentifierGenerator
    )
  }

  // Concrete (non-wildcard) requested indices; resolution never touches the cluster state.
  private def createConcreteNamesBlockContext(): GeneralIndexRequestBlockContext = {
    val requested = (0 until requestedIndices)
      .map(idx => RequestedIndex(ClusterIndexName.fromString(s"logs-app-${idx % patterns}-day-$idx").get, excluded = false))
      .toCovariantSet
    new IndexRequestContext(realisticHeaders(createCredentials("user1", "pass1")), requested)
      .initialBlockContext(noBlock)
  }

  // Requested `logs-app-*` must EXPAND against a 200-index cluster (allIndicesAndAliases).
  private def createWildcardBlockContext(): GeneralIndexRequestBlockContext = {
    val clusterIndices = (0 until 200)
      .map { idx =>
        new FullLocalIndexWithAliases(
          IndexName.Full(nes(s"logs-app-${idx % patterns}-day-$idx")),
          IndexAttribute.Opened,
          Set.empty
        )
      }
      .toCovariantSet
    val wildcardRequested =
      Set(RequestedIndex(ClusterIndexName.fromString("logs-app-*").get, excluded = false))
    new IndexRequestContext(
      realisticHeaders(createCredentials("user1", "pass1")),
      wildcardRequested,
      esServicesWithIndices(clusterIndices)
    ).initialBlockContext(noBlock)
  }
}
