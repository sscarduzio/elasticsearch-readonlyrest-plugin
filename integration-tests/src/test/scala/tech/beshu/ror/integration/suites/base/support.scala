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
package tech.beshu.ror.integration.suites.base

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.scalatest.Suite
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.providers.*
import tech.beshu.ror.utils.containers.{DependencyDef, EsClusterContainer, EsClusterProvider, EsRemoteClustersContainer}

object support {

  trait BaseEsClusterIntegrationTest
      extends RorSettingsFileNameProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite & EsClusterProvider & ESVersionSupport =>

    override lazy val container: EsClusterContainer = clusterContainer

    def clusterContainer: EsClusterContainer
  }

  trait BaseEsRemoteClusterIntegrationTest
      extends RorSettingsFileNameProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite & EsClusterProvider & ESVersionSupport =>

    override lazy val container: EsRemoteClustersContainer = remoteClusterContainer

    def remoteClusterContainer: EsRemoteClustersContainer
  }

  trait BaseManyEsClustersIntegrationTest
      extends RorSettingsFileNameProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite & EsClusterProvider & ESVersionSupport =>

    import com.dimafeng.testcontainers.LazyContainer.*

    override lazy val container: MultipleContainers =
      MultipleContainers(clusterContainers.map(containerToLazyContainer(_)).toList*)

    def clusterContainers: NonEmptyList[EsClusterContainer]
  }

  // Parallelism model (read before adding a suite): IT suites run SERIALLY within a Gradle worker
  // JVM; parallelism is across separate SHARD invocations (-PshardCount, IT_PARALLELISM in CI), each
  // its own worker JVM. Each worker JVM is a separate process with its own ES + Docker network, so
  // cross-worker interference is impossible by construction. Single-node suites that mix in SingletonPluginTestSupport share
  // ONE mutable ES per worker — safe only because the scalatest-junit engine runs suites one-at-a-time
  // in a JVM; the singleton's acquire/release latch fails the build loudly if that ever stops holding.
  // Do not add ScalaTest ParallelTestExecution or junit parallel config (the latter is guarded in
  // integration-tests/build.gradle).
  trait BaseSingleNodeEsClusterTest
      extends RorSettingsFileNameProvider
      with SingleClientSupport
      with TestSuiteWithClosedTaskAssertion
      with NodeInitializerProvider {
    this: Suite & EsClusterProvider & ESVersionSupport =>

    def clusterDependencies: List[DependencyDef] = List.empty
  }

  trait SingleClientSupport extends SingleClient with SingleEsTarget
  trait MultipleClientsSupport extends MultipleClients with MultipleEsTargets
}
