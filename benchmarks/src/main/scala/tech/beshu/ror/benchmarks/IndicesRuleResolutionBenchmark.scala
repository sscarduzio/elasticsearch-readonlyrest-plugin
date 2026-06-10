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

import cats.data.NonEmptyList
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.{resolveAll, resolveAllIfPreResolved}
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Quantifies the IndicesRule static-config optimization: the old path re-ran `resolveAll` +
 * `toCovariantSet` + the local/remote split + `PatternsMatcher.create` for the local indices and
 * the remote cluster names on every request; the new path reuses the AllowedClusterIndices holder
 * built once at rule construction, so per request only the match call remains. A typical
 * local-indices request over a 10-glob config (7 local + 3 remote patterns) is modeled.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class IndicesRuleResolutionBenchmark {

  // Static (AlreadyResolved) config: resolveAll never touches the block context, so null is safe.
  private val noBlockContext: BlockContext = null

  private val configuredPatterns = List(
    "logs-*", "metrics-*", "app-1-*", "app-2-*", "audit-2024-*", "dev-*", "team-a-*",
    "remote-east:logs-*", "remote-west:metrics-*", "remote-*:shared-*"
  )

  private val settings: NonEmptyList[RuntimeMultiResolvableVariable[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(
      configuredPatterns.map(p => AlreadyResolved(NonEmptyList.one(ClusterIndexName.fromString(p).get)))
    )

  private var candidate: ClusterIndexName = scala.compiletime.uninitialized
  private var precomputedLocalMatcher: PatternsMatcher[ClusterIndexName.Local] = scala.compiletime.uninitialized
  private var precomputedClusterNamesMatcher: PatternsMatcher[ClusterIndexName.Remote.ClusterName] = scala.compiletime.uninitialized

  private def splitIntoRemoteAndLocal(all: Set[ClusterIndexName]): (Set[ClusterIndexName.Remote], Set[ClusterIndexName.Local]) = {
    val remoteIndices = Set.newBuilder[ClusterIndexName.Remote]
    val localIndices = Set.newBuilder[ClusterIndexName.Local]
    all.foreach {
      case localIndex: ClusterIndexName.Local => localIndices += localIndex
      case remoteIndex: ClusterIndexName.Remote => remoteIndices += remoteIndex
    }
    (remoteIndices.result(), localIndices.result())
  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    val all = resolveAllIfPreResolved(settings)
      .map(_.toList.toCovariantSet)
      .getOrElse(throw new IllegalStateException("expected static settings"))
    val (remote, local) = splitIntoRemoteAndLocal(all)
    precomputedLocalMatcher = PatternsMatcher.create(local)
    precomputedClusterNamesMatcher = PatternsMatcher.create(remote.map(_.cluster))
    candidate = ClusterIndexName.fromString("unrelated-index-123").get
  }

  // Old per-request work: resolve, split, compile the local and remote-cluster-name matchers, match.
  @Benchmark
  def oldPath_resolveSplitCompilePerRequest(bh: Blackhole): Unit = {
    val all = resolveAll(settings, noBlockContext).toCovariantSet
    val (remote, local) = splitIntoRemoteAndLocal(all)
    val localMatcher = PatternsMatcher.create(local)
    val clusterNamesMatcher = PatternsMatcher.create(remote.map(_.cluster))
    bh.consume(clusterNamesMatcher)
    bh.consume(localMatcher.`match`(candidate.asInstanceOf[ClusterIndexName.Local]))
  }

  // New per-request work: just the match against the holder's precompiled matcher.
  @Benchmark
  def newPath_precomputedHolder(bh: Blackhole): Unit = {
    bh.consume(precomputedClusterNamesMatcher)
    bh.consume(precomputedLocalMatcher.`match`(candidate.asInstanceOf[ClusterIndexName.Local]))
  }
}
