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
 * Docker network shared by all containers created within this test JVM.
 *
 * Suites wire containers together by network alias derived from the cluster name (e.g.
 * `discovery.seed_hosts`, `<node>:9300`, LDAP/Toxiproxy hostnames), and those names are fixed
 * per suite type (several suites use `ROR1`). Unlike the previous `Network.SHARED` (one global,
 * reused network), this is a uniquely-named (UUID) network created per JVM, so two test JVMs can
 * never cross-wire clusters through duplicate aliases.
 *
 * NOTE: today there is exactly ONE test JVM — the maiflai scalatest runner maps
 * `maxParallelForks` to in-JVM suite THREADS (ScalaTest `-PS<n>`), not forked JVMs — so this
 * isolation becomes load-bearing only once integration tests move to real per-JVM forking
 * (Gradle-native test execution; planned follow-up). Until then `maxParallelForks` must stay 1:
 * concurrent suites in one JVM would share this network AND the singleton ES container.
 */
object TestNetwork {

  // `org.gradle.test.worker` is set only inside real Gradle test-worker JVMs; under the maiflai
  // runner it is absent ("local"). Kept as a label so per-worker networks are identifiable in
  // `docker network ls` once real forking lands. Uniqueness comes from the UUID network name,
  // not from this label.
  lazy val perJvm: Network = {
    val workerId = Option(System.getProperty("org.gradle.test.worker")).getOrElse("local")
    Network
      .builder()
      .createNetworkCmdModifier(cmd => cmd.withLabels(java.util.Map.of("ror-test-jvm", workerId)))
      .build()
  }
}
