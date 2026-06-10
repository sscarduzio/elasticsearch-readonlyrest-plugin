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
 * Docker network shared by all containers created within this test JVM, ISOLATED between JVMs.
 *
 * Suites wire containers together by network alias derived from the cluster name (e.g.
 * `discovery.seed_hosts`, `<node>:9300`, LDAP/Toxiproxy hostnames), and those names are fixed
 * per suite type (several suites use `ROR1`). Unlike the previous `Network.SHARED` (one global,
 * reused network), this is a uniquely-named (UUID) network created per JVM.
 *
 * Integration tests run through Gradle's native test machinery (JUnit Platform scalatest
 * engine), so `maxParallelForks > 1` spawns REAL worker JVMs. Each worker then gets its own
 * network, its own singleton ES container and its own DNS namespace — alias-colliding suites
 * can never cross-wire clusters across workers, and within one worker suites run sequentially,
 * exactly like the serial baseline.
 */
object TestNetwork {

  // `org.gradle.test.worker` is set inside Gradle test-worker JVMs ("local" outside Gradle,
  // e.g. when run from an IDE). A label only — uniqueness comes from the UUID network name.
  // It makes per-worker networks identifiable in `docker network ls`.
  lazy val perJvm: Network = {
    val workerId = Option(System.getProperty("org.gradle.test.worker")).getOrElse("local")
    Network
      .builder()
      .createNetworkCmdModifier(cmd => cmd.withLabels(java.util.Map.of("ror-test-jvm", workerId)))
      .build()
  }
}
