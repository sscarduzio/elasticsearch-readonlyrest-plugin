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
 * Docker network shared by all containers within a single test JVM (Gradle test fork), but
 * ISOLATED between forks.
 *
 * Why this exists: integration suites wire containers together by network alias derived from the
 * cluster name (e.g. `discovery.seed_hosts`, `<node>:9300`, LDAP/Toxiproxy hostnames). Those
 * cluster names are fixed per suite type, not per fork. With the previous `Network.SHARED`
 * (a single process-wide network) this was fine while tests ran serially (`maxParallelForks = 1`),
 * but under parallel forks two forks running the same multi-node/cross-cluster suite would put
 * containers with the SAME alias on the SAME network — Docker round-robins the duplicate alias and
 * a node in one fork's cluster can discover/join another fork's node, cross-wiring clusters and
 * causing flaky failures.
 *
 * Each test fork is a separate JVM, so a process-wide `lazy val` here yields exactly one network
 * per fork; aliases only ever collide within a fork (where the containers genuinely belong
 * together and run sequentially). This makes `maxParallelForks > 1` safe.
 */
object TestNetwork {

  // One network per test JVM (= per Gradle test fork). `newNetwork()` yields a uniquely-named
  // (UUID) Docker network, so different forks never share one even when running the same suite.
  // A fork-id label is attached purely to make the isolation visible in `docker network ls`.
  lazy val perFork: Network = {
    val workerId = Option(System.getProperty("org.gradle.test.worker")).getOrElse("local")
    Network
      .builder()
      .createNetworkCmdModifier(cmd => cmd.withLabels(java.util.Map.of("ror-test-fork", workerId)))
      .build()
  }
}
