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

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.{Container, SingleContainer}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.EsClusterSettings.NodeType
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch.ClusterManager

import scala.language.existentials

class EsClusterContainer private[containers](val esClusterSettings: EsClusterSettings,
                                             val nodeCreators: NonEmptyList[StartedClusterDependencies => EsContainer],
                                             dependencies: List[DependencyDef])
  extends Container {

  private var clusterNodes = List.empty[EsContainer]
  private var aStartedDependencies = StartedClusterDependencies(Nil)

  def nodes: List[EsContainer] = this.clusterNodes

  def esVersion: String = nodes.headOption match {
    case Some(head) => head.esVersion
    case None => throw new IllegalStateException("Nodes of cluster have not started yet. Cannot determine ES version.")
  }

  override def start(): Unit = {
    aStartedDependencies = DependencyRunner.startDependencies(dependencies)
    clusterNodes = startClusterNodes(aStartedDependencies)
  }

  override def stop(): Unit = {
    clusterNodes.foreach(_.stop())
    aStartedDependencies.values.foreach(_.container.stop())
  }

  private def startClusterNodes(dependencies: StartedClusterDependencies) = {
    val nodes = nodeCreators.toList.map(creator => creator(dependencies))
    startContainersAsynchronously(nodes)
    nodes
  }

  private def startContainersAsynchronously(containers: Iterable[SingleContainer[_]]): Unit = {
    Task
      .gatherUnordered {
        containers.map(c => Task(c.start()))
      }
      .runSyncUnsafe()
  }

  def resolvedRorConfig(config: String, mode: Mode): String = {
    RorConfigAdjuster.adjustUsingDependencies(config, aStartedDependencies, mode)
  }
}

class EsRemoteClustersContainer private[containers](val localCluster: EsClusterContainer,
                                                    remoteClusters: NonEmptyList[EsClusterContainer],
                                                    remoteClusterSetup: SetupRemoteCluster)
  extends Container {

  override def start(): Unit = {
    remoteClusters.toList.foreach(_.start())
    localCluster.start()
    remoteClustersInitializer(
      localCluster,
      remoteClusterSetup.remoteClustersConfiguration(remoteClusters)
    )
  }

  override def stop(): Unit = {
    localCluster.stop()
    remoteClusters.toList.foreach(_.stop())
  }

  private def remoteClustersInitializer(container: EsClusterContainer,
                                        remoteClustersConfig: Map[String, EsClusterContainer]): Unit = {
    val clusterManager = new ClusterManager(container.nodes.head.adminClient, esVersion = container.nodes.head.esVersion)
    val result = clusterManager.configureRemoteClusters(
      remoteClustersConfig.mapValues(_.nodes.map(c => s""""${c.esConfig.nodeName}:9300""""))
    )

    result.responseCode match {
      case 200 =>
      case other =>
        throw new IllegalStateException(s"Cannot initialize remote cluster settings - response code: $other")
    }
  }
}

final case class ContainerSpecification(environmentVariables: Map[String, String],
                                        additionalElasticsearchYamlEntries: Map[String, String])
object ContainerSpecification {
  lazy val empty: ContainerSpecification = ContainerSpecification(
    environmentVariables = Map.empty,
    additionalElasticsearchYamlEntries = Map.empty
  )
}

trait SetupRemoteCluster {
  def remoteClustersConfiguration(remoteClusters: NonEmptyList[EsClusterContainer]): Map[String, EsClusterContainer]
}

final case class EsClusterSettings private(clusterName: String,
                                           nodeTypes: NonEmptyList[NodeType],
                                           nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                           containerSpecification: ContainerSpecification,
                                           dependentServicesContainers: List[DependencyDef],
                                           esVersion: EsVersion)

object EsClusterSettings {

  def create(clusterName: String,
             securityType: SecurityType,
             numberOfInstances: Int Refined Positive = 1,
             nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
             containerSpecification: ContainerSpecification = ContainerSpecification.empty,
             dependentServicesContainers: List[DependencyDef] = Nil,
             esVersion: EsVersion = EsVersion.DeclaredInProject): EsClusterSettings = {
    EsClusterSettings(
      clusterName,
      NonEmptyList.one(NodeType(securityType, numberOfInstances)),
      nodeDataInitializer,
      containerSpecification,
      dependentServicesContainers,
      esVersion
    )
  }

  def createMixedCluster(clusterName: String,
                         nodeTypes: NonEmptyList[NodeType],
                         nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
                         containerSpecification: ContainerSpecification = ContainerSpecification.empty,
                         dependentServicesContainers: List[DependencyDef] = Nil,
                         esVersion: EsVersion = EsVersion.DeclaredInProject): EsClusterSettings = {
    EsClusterSettings(
      clusterName,
      nodeTypes,
      nodeDataInitializer,
      containerSpecification,
      dependentServicesContainers,
      esVersion
    )
  }

  final case class NodeType(securityType: SecurityType,
                            numberOfInstances: Int Refined Positive = 1)

}

trait EsVersion
object EsVersion {
  case object DeclaredInProject extends EsVersion
  final case class SpecificVersion(moduleName: String) extends EsVersion
}

sealed trait SecurityType
object SecurityType {
  final case class RorWithXpackSecurity(attributes: ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes) extends SecurityType
  final case class RorSecurity(attributes: ReadonlyRestPlugin.Config.Attributes) extends SecurityType
  final case class XPackSecurity(attributes: XpackSecurityPlugin.Config.Attributes) extends SecurityType
  case object NoSecurityCluster extends SecurityType
}

final case class DependencyDef(name: String, containerCreator: Coeval[SingleContainer[GenericContainer[_]]], originalPort: Int)
