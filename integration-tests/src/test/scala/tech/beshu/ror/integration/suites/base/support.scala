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
import tech.beshu.ror.utils.containers.providers._
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterProvider, EsContainerCreator, EsRemoteClustersContainer}

object support {

  trait BaseEsClusterIntegrationTest
    extends RorConfigFileNameProvider
      with EsClusterProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite with EsContainerCreator =>

    override lazy val container: EsClusterContainer = clusterContainer

    def clusterContainer: EsClusterContainer
  }

  trait BaseEsRemoteClusterIntegrationTest
    extends RorConfigFileNameProvider
      with EsClusterProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite with EsContainerCreator =>

    override lazy val container: EsRemoteClustersContainer = remoteClusterContainer

    def remoteClusterContainer: EsRemoteClustersContainer
  }

  trait BaseManyEsClustersIntegrationTest
    extends RorConfigFileNameProvider
      with EsClusterProvider
      with MultipleClientsSupport
      with TestSuiteWithClosedTaskAssertion
      with ForAllTestContainer {
    this: Suite with EsContainerCreator =>

    import com.dimafeng.testcontainers.LazyContainer._

    override lazy val container: MultipleContainers =
      MultipleContainers(clusterContainers.map(containerToLazyContainer(_)).toList: _*)

    def clusterContainers: NonEmptyList[EsClusterContainer]
  }

  trait BaseSingleNodeEsClusterTest
    extends RorConfigFileNameProvider
      with EsClusterProvider
      with SingleClientSupport
      with TestSuiteWithClosedTaskAssertion
      with NodeInitializerProvider {
    this: Suite with EsContainerCreator =>
  }

  trait SingleClientSupport extends SingleClient with SingleEsTarget
  trait MultipleClientsSupport extends MultipleClients with MultipleEsTargets
}
