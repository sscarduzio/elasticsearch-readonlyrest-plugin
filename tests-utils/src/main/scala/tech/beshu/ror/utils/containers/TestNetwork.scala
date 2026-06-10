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
 * One uniquely-named (UUID) Docker network per test JVM (vs the old global `Network.SHARED`),
 * so parallel test workers can't cross-wire clusters whose suites reuse the same aliases (e.g. `ROR1_1`).
 */
object TestNetwork {

  // workerId: set by Gradle test workers, "local" outside Gradle. Label aids `docker network ls`;
  // uniqueness comes from the UUID network name.
  lazy val perJvm: Network = {
    val workerId = Option(System.getProperty("org.gradle.test.worker")).getOrElse("local")
    Network
      .builder()
      .createNetworkCmdModifier(cmd => cmd.withLabels(java.util.Map.of("ror-test-jvm", workerId)))
      .build()
  }
}
