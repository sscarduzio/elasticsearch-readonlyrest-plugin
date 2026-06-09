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
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.{resolveAll, resolveAllIfPreResolved}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Quantifies the `resolveAll`-bypass optimization applied to `DataStreamsRule`,
 * `RepositoriesRule` and `SnapshotsRule` (where the allowed values are STATICALLY
 * configured — no `@{user}`-style runtime variables, the common case).
 *
 * It compares the two per-request code paths directly, using the real production
 * helpers (`resolveAll`, `resolveAllIfPreResolved`, `PatternsMatcher.create`) on real
 * `AlreadyResolved[DataStreamName]` settings — no rule/mock machinery, because for a
 * static config `AlreadyResolved.resolve` returns its value and never touches the
 * `BlockContext` (so a null context is safe and never dereferenced here).
 *
 *  - `oldPath_resolvePerRequest`  : what the rules did BEFORE — every request runs
 *    `resolveAll(settings).toCovariantSet`, the wildcard `contains` check, and
 *    `PatternsMatcher.create(set)` (glob classification + compilation).
 *  - `newPath_precomputed`        : what the rules do AFTER — reuse the holder built
 *    once at construction; per request is just a field read + a match call.
 *
 * `matchOnly` is the shared downstream cost both paths incur (one `match` call); it
 * isolates how much of each path is pure resolution/compilation overhead.
 *
 * Add `-prof gc` (via -PjmhArgs) to see the per-request allocation removed on the
 * static path.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class RuleStaticResolutionBenchmark {

  // The block context is never dereferenced for a static (AlreadyResolved) config.
  private val noBlockContext: BlockContext = null

  private val fewSettings = staticSettings(3)
  private val manySettings = staticSettings(30)

  // Built once, mirroring what the optimized rules cache at construction time.
  private var fewPrecomputed: PatternsMatcher[DataStreamName] = scala.compiletime.uninitialized
  private var manyPrecomputed: PatternsMatcher[DataStreamName] = scala.compiletime.uninitialized

  // A candidate that misses every pattern (forces a full scan, the worst case).
  private var candidate: DataStreamName = scala.compiletime.uninitialized

  private def staticSettings(n: Int): NonEmptyList[RuntimeMultiResolvableVariable[DataStreamName]] = {
    val values = (0 until n).map(i => DataStreamName.fromString(s"data-stream-$i-*").get).toList
    NonEmptyList.fromListUnsafe(values.map(v => AlreadyResolved(NonEmptyList.one(v))))
  }

  @Setup(Level.Trial)
  def setup(): Unit = {
    fewPrecomputed = precompute(fewSettings)
    manyPrecomputed = precompute(manySettings)
    candidate = DataStreamName.fromString("totally-unrelated-stream").get
  }

  private def precompute(settings: NonEmptyList[RuntimeMultiResolvableVariable[DataStreamName]]): PatternsMatcher[DataStreamName] =
    resolveAllIfPreResolved(settings)
      .map(values => PatternsMatcher.create(values.toList.toCovariantSet))
      .getOrElse(throw new IllegalStateException("expected static settings"))

  // --- OLD path: resolve + build matcher on every request -------------------------

  private def oldPath(settings: NonEmptyList[RuntimeMultiResolvableVariable[DataStreamName]]): Boolean = {
    val allowed = resolveAll(settings, noBlockContext).toCovariantSet
    if (allowed.contains(DataStreamName.All) || allowed.contains(DataStreamName.Wildcard)) true
    else PatternsMatcher.create(allowed).`match`(candidate)
  }

  @Benchmark
  def oldPath_resolvePerRequest_fewPatterns(bh: Blackhole): Unit = bh.consume(oldPath(fewSettings))

  @Benchmark
  def oldPath_resolvePerRequest_manyPatterns(bh: Blackhole): Unit = bh.consume(oldPath(manySettings))

  // --- NEW path: reuse the precomputed matcher ------------------------------------

  @Benchmark
  def newPath_precomputed_fewPatterns(bh: Blackhole): Unit = bh.consume(fewPrecomputed.`match`(candidate))

  @Benchmark
  def newPath_precomputed_manyPatterns(bh: Blackhole): Unit = bh.consume(manyPrecomputed.`match`(candidate))
}
