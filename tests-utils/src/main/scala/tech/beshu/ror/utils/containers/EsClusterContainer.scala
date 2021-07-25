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
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.EsClusterSettings.EsVersion
import tech.beshu.ror.utils.elasticsearch.ClusterManager

import scala.collection.immutable.Seq
import scala.language.existentials

class EsClusterContainer private[containers](val rorContainerSpecification: ContainerSpecification,
                                             val nodeCreators: NonEmptyList[StartedClusterDependencies => EsContainer],
                                             dependencies: List[DependencyDef])
  extends Container {

  private val depsContainers: Seq[(DependencyDef, SingleContainer[GenericContainer[_]])] =
    dependencies.map(d => (d, d.containerCreator.apply()))

  private var clusterNodes = List.empty[EsContainer]
  private var aStartedDependencies = StartedClusterDependencies(Nil)

  def nodes: List[EsContainer] = this.clusterNodes

  def startedDependencies: StartedClusterDependencies = aStartedDependencies

  def esVersion: String = nodes.headOption match {
    case Some(head) => head.esVersion
    case None => throw new IllegalStateException("Nodes of cluster have not started yet. Cannot determine ES version.")
  }

  override def start(): Unit = {
    aStartedDependencies = startDependencies()
    clusterNodes = startClusterNodes(aStartedDependencies)
  }

  override def stop(): Unit = {
    clusterNodes.foreach(_.stop())
    depsContainers.foreach(_._2.stop())
  }

  private def startDependencies() = {
    startContainersAsynchronously(depsContainers.map(_._2))
    StartedClusterDependencies {
      depsContainers
        .map { case (dependencyDef, container) =>
          StartedDependency(dependencyDef.name, container, dependencyDef.originalPort)
        }
        .toList
    }
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
      remoteClustersConfig.mapValues(_.nodes.map(c => s""""${c.name}:9300""""))
    )

    result.responseCode match {
      case 200 =>
      case other =>
        throw new IllegalStateException(s"Cannot initialize remote cluster settings - response code: $other")
    }
  }
}

final case class ContainerSpecification(environmentVariables: Map[String, String])

trait SetupRemoteCluster {
  def remoteClustersConfiguration(remoteClusters: NonEmptyList[EsClusterContainer]): Map[String, EsClusterContainer]
}

final case class EsClusterSettings(name: String,
                                   numberOfInstances: Int = 1,
                                   nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
                                   rorContainerSpecification: ContainerSpecification = ContainerSpecification(Map.empty),
                                   dependentServicesContainers: List[DependencyDef] = Nil,
                                   xPackSupport: Boolean,
                                   fullXPackSupport: Boolean = false,
                                   configHotReloadingEnabled: Boolean = false,
                                   customRorIndexName: Option[String] = None,
                                   internodeSslEnabled: Boolean = false,
                                   esVersion: EsVersion = EsVersion.DeclaredInProject,
                                   externalSslEnabled: Boolean = true,
                                   enableXPackSsl: Boolean = false,
                                   forceNonOssImage: Boolean = false)(implicit val rorConfigFileName: String)

object EsClusterSettings {
  val basic = EsClusterSettings(name = "ROR_SINGLE", xPackSupport = false)("/basic/readonlyrest.yml")

  trait EsVersion
  object EsVersion {
    case object DeclaredInProject extends EsVersion
    final case class SpecificVersion(moduleName: String) extends EsVersion
  }
}

final case class DependencyDef(name: String, containerCreator: Coeval[SingleContainer[GenericContainer[_]]], originalPort: Int)
