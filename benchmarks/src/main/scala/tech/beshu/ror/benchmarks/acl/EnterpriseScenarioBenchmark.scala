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
package tech.beshu.ror.benchmarks.acl

import cats.data.{NonEmptyList, NonEmptySet}
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersAndRule
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
 * Tier-1 composite KPI ("worst real customer"): 100 blocks, each with a groups rule over 100
 * configured groups (user has 100 groups), an indices rule with 120 patterns, an actions rule
 * and a headers rule, evaluated against a realistic 20-header search request.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class EnterpriseScenarioBenchmark {

  private implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)

  private val blockCount = 100
  private val configuredGroupCount = 100
  private val userGroupCount = 100
  private val indexPatternCount = 120

  private var acl: EnabledAccessControlList = scala.compiletime.uninitialized
  private var permitContext: IndexRequestContext = scala.compiletime.uninitialized
  private var denyContext: IndexRequestContext = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    acl = createAcl(NonEmptyList.fromListUnsafe((1 to blockCount).map(createExampleBlock).toList))
    // Last block's credentials, so blocks 1..N-1 deny on auth and block N permits.
    permitContext = createContext(createCredentials(s"user$blockCount", s"pass$blockCount"))
    denyContext = createContext(createCredentials("nobody", "nothing"))
    assertRequestAllowedBy(acl, permitContext, expectedBlockName = s"block$blockCount")
    assertRequestForbiddenBy(acl, denyContext)
  }

  @Benchmark
  def permitPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(permitContext).runSyncUnsafe())

  @Benchmark
  def denyPath(bh: Blackhole): Unit =
    bh.consume(acl.handleRegularRequest(denyContext).runSyncUnsafe())

  private def createExampleBlock(blockIndex: Int): Block = {
    val rules = NonEmptyList.of[Rule](
      createAnyOfGroupsRule(s"user$blockIndex", s"pass$blockIndex", configuredGroupCount, userGroupCount),
      createActionsRule(),
      createIndicesRule(),
      createHeadersRule()
    )
    // .sorted = production RuleOrdering (what Block.createFrom applies); validation is config-time-only.
    new Block(Block.Name(s"block$blockIndex"), Block.Policy.Allow, rules.sorted, Block.Audit.Enabled())
  }

  private def createIndicesRule(): IndicesRule = {
    val patterns = (0 until indexPatternCount)
      .map(idx => AlreadyResolved(NonEmptyList.one(ClusterIndexName.fromString(s"logs-app-$idx-*").get)): RuntimeMultiResolvableVariable[ClusterIndexName])
    new IndicesRule(
      IndicesRule.Settings(NonEmptySet.of(patterns.head, patterns.tail*), mustInvolveIndices = false),
      RandomBasedUniqueIdentifierGenerator
    )
  }

  private def createActionsRule(): ActionsRule =
    new ActionsRule(ActionsRule.Settings(NonEmptySet.of(
      Action("indices:admin/aliases/get"), Action("indices:data/write/*"), searchAction
    )))

  private def createHeadersRule(): HeadersAndRule =
    new HeadersAndRule(HeadersAndRule.Settings(NonEmptySet.of(
      AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Custom-1")), nes("value-1"))),
      AccessRequirement.MustBePresent(Header(Header.Name(nes("X-Filler-1")), nes("value-1")))
    )))

  private def createContext(credentials: Credentials): IndexRequestContext = {
    val requested = (0 until 5)
      .map(idx => RequestedIndex(ClusterIndexName.fromString(s"logs-app-$idx-2026").get, excluded = false))
      .toCovariantSet
    new IndexRequestContext(realisticHeaders(credentials), requested)
  }
}
