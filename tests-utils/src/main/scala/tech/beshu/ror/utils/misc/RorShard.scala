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
package tech.beshu.ror.utils.misc

/**
 * The identity of this test JVM within a sharded integration-test run.
 *
 * Sharded runs (`integration-tests:shardedTest`) put K test JVMs on ONE host; the test task
 * passes each JVM its index via the `ror.shard.index` system property (see
 * integration-tests/build.gradle). Everything host-global that would collide between shards —
 * native-ES ports, install/data dirs, WireMock ports — keys its per-shard window off this
 * object instead of reading the property directly.
 */
object RorShard {

  private val SystemPropertyName = "ror.shard.index"

  /** This JVM's shard index, when running as part of a sharded run. */
  val index: Option[Int] = Option(Integer.getInteger(SystemPropertyName)).map(_.intValue())

  /**
   * Width of each shard's port window. Invariant carried by [[shardedBasePort]]: every fixed
   * base port must be at least this far from the next base, and no per-shard range may grow
   * past it (bases used today: 8080/8081 WireMock, 9200/9300 native ES).
   */
  val PortWindowSize: Int = 1000

  /**
   * Per-shard port window offset: shard k shifts any fixed base port by k*[[PortWindowSize]],
   * keeping the windows of concurrently running shards disjoint.
   */
  val portOffset: Int = index.getOrElse(0) * PortWindowSize

  /**
   * Shifts a fixed base port into this shard's window. `rangeWidth` is how many consecutive
   * ports the caller uses from the base; the require turns a window overflow (range crossing
   * into shard k+1's window) into a loud failure instead of a flaky cross-shard bind error.
   */
  def shardedBasePort(base: Int, rangeWidth: Int = 1): Int = {
    require(
      rangeWidth > 0 && rangeWidth <= PortWindowSize,
      s"port range width $rangeWidth exceeds the per-shard window of $PortWindowSize"
    )
    base + portOffset
  }

  /** Suffixes a path/name with the shard index so sibling shards get disjoint directories. */
  def shardedName(base: String): String = index.fold(base)(i => s"$base-shard-$i")
}
