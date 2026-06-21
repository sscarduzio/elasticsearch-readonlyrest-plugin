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
package tech.beshu.ror.utils.containers

import org.testcontainers.containers.Network

/**
 * Docker network for ES test containers.
 *
 * Default (no sharding) = `Network.SHARED`, exactly as master used for 5+ years — multi-node clusters
 * resolve each other by alias (ROR1_1, ROR1_2) on it reliably. A custom per-JVM network is ONLY needed
 * when >1 sharded worker JVM runs on the SAME Docker daemon at once (shardCount>1): then two JVMs could
 * cross-wire clusters that reuse the same aliases, so each gets its own UUID-named network.
 *
 * Using a custom (non-SHARED) network unconditionally REGRESSED every multi-node leg — clusters failed
 * to form (peer `failed to resolve host`, 5-min readiness timeout). So we only diverge from SHARED when
 * sharding actually requires it. -PshardCount is surfaced to tests via the `it.shardCount` system prop.
 */
object TestNetwork {

  lazy val perJvm: Network = {
    val shardCount = Option(System.getProperty("it.shardCount")).map(_.toInt).getOrElse(1)
    if (shardCount <= 1) {
      Network.SHARED
    } else {
      // workerId: set by Gradle test workers, "local" outside Gradle. Label aids `docker network ls`;
      // uniqueness comes from the UUID network name.
      val workerId = Option(System.getProperty("org.gradle.test.worker")).getOrElse("local")
      Network
        .builder()
        .createNetworkCmdModifier(cmd => cmd.withLabels(java.util.Map.of("ror-test-jvm", workerId)))
        .build()
    }
  }
}
