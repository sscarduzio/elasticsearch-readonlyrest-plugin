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
   * Per-shard port window offset: shard k shifts any fixed base port by k*1000, keeping the
   * windows of concurrently running shards disjoint (bases used today: 8080/8081 WireMock,
   * 9200/9300 native ES — all far more than 1000 apart from the next base).
   */
  val portOffset: Int = index.getOrElse(0) * 1000

  /** Suffixes a path/name with the shard index so sibling shards get disjoint directories. */
  def shardedName(base: String): String = index.fold(base)(i => s"$base-shard-$i")
}
