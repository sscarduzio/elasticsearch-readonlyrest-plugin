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

import java.io.File

import cats.implicits._
import cats.data.NonEmptyList
import com.dimafeng.testcontainers.{Container, GenericContainer}
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import org.junit.runner.Description
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._

import scala.language.existentials
import scala.util.Try

object ReadonlyRestEsCluster {

  def createLocalClusterContainer(name: String,
                                  rorConfigFileName: String,
                                  clusterSettings: AdditionalClusterSettings = AdditionalClusterSettings()): ReadonlyRestEsClusterContainer = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("ES Cluster should have at least one instance")

    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion
    val rorConfigFile = ContainerUtils.getResourceFile(rorConfigFileName)
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList.map(idx => s"${name}_$idx"))

    new ReadonlyRestEsClusterContainer(
      nodeNames.map { name =>
        val containerConfig = ReadonlyRestEsContainer.Config(
          nodeName = name,
          nodes = nodeNames,
          esVersion = esVersion,
          rorPluginFile = rorPluginFile,
          rorConfigFile = rorConfigFile,
          configHotReloadingEnabled = clusterSettings.configHotReloadingEnabled,
          internodeSslEnabled = clusterSettings.internodeSslEnabled,
          xPackSupport = clusterSettings.xPackSupport)
        Task(ReadonlyRestEsContainer.create(containerConfig, clusterSettings.nodeDataInitializer))
      },
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer
    )
  }

  def createRemoteClustersContainer(localClusters: NonEmptyList[LocalClusterDef],
                                    remoteClustersInitializer: RemoteClustersInitializer): ReadonlyRestEsRemoteClustersContainer = {
    val startedClusters = localClusters
      .map { cluster =>
        createLocalClusterContainer(
          cluster.name,
          cluster.rorConfigFileName,
          AdditionalClusterSettings(nodeDataInitializer = cluster.nodeDataInitializer))
      }
    new ReadonlyRestEsRemoteClustersContainer(startedClusters, remoteClustersInitializer)
  }

  final case class AdditionalClusterSettings(numberOfInstances: Int = 1,
                                             nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
                                             clusterInitializer: ReadonlyRestEsClusterInitializer = NoOpReadonlyRestEsClusterInitializer,
                                             dependentServicesContainers: List[DependencyDef] = Nil,
                                             xPackSupport: Boolean = false,
                                             configHotReloadingEnabled: Boolean = true,
                                             internodeSslEnabled: Boolean = false)
}

final case class LocalClusterDef(name: String, rorConfigFileName: String, nodeDataInitializer: ElasticsearchNodeDataInitializer)
final case class DependencyDef(name: String, containerCreator: Coeval[GenericContainer])

class ReadonlyRestEsClusterContainer private[containers](rorClusterContainers: NonEmptyList[Task[ReadonlyRestEsContainer]],
                                                         dependencies: List[DependencyDef],
                                                         clusterInitializer: ReadonlyRestEsClusterInitializer)
  extends Container {

  val nodesContainers: NonEmptyList[ReadonlyRestEsContainer] = {
    NonEmptyList.fromListUnsafe(Task.gather(rorClusterContainers.toList).runSyncUnsafe())
  }

  val depsContainers: List[(DependencyDef, GenericContainer)] =
    dependencies.map(d => (d, d.containerCreator.apply()))

  val esVersion: String = nodesContainers.head.esVersion

  override def starting()(implicit description: Description): Unit = {
    Task.gather(depsContainers.map(s => Task(s._2.starting()(description)))).runSyncUnsafe()

    Task.gather(nodesContainers.toList.map(s => Task(s.starting()(description)))).runSyncUnsafe()
    clusterInitializer.initialize(nodesContainers.head.adminClient, this)
  }

  override def finished()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.succeeded()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.failed(e)(description))

}

class ReadonlyRestEsRemoteClustersContainer private[containers](val localClusters: NonEmptyList[ReadonlyRestEsClusterContainer],
                                                                remoteClustersInitializer: RemoteClustersInitializer)
  extends Container {

  override def starting()(implicit description: Description): Unit = {
    localClusters.map(_.starting()(description))
    val config = remoteClustersInitializer.remoteClustersConfiguration(localClusters.map(_.nodesContainers.head))
    localClusters.toList.foreach { container =>
      remoteClustersInitializer(container.nodesContainers.head, config)
    }
  }

  override def finished()(implicit description: Description): Unit =
    localClusters.toList.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit =
    localClusters.toList.foreach(_.succeeded()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit =
    localClusters.toList.foreach(_.failed(e)(description))

  private def remoteClustersInitializer(container: ReadonlyRestEsContainer,
                                        remoteClustersConfig: Map[String, NonEmptyList[ReadonlyRestEsContainer]]): Unit = {
    def createRemoteClusterSettingsRequest(adminClient: RestClient) = {
      val remoteClustersConfigString = remoteClustersConfig
        .map { case (name, remoteClusterContainers) =>
          s""""$name": { "seeds": [ ${remoteClusterContainers.toList.map(c => s""""${c.name}:9300"""").mkString(",")} ] }"""
        }
        .mkString(",\n")

      val request = new HttpPut(adminClient.from("_cluster/settings"))
      request.setHeader("Content-Type", "application/json")
      request.setEntity(new StringEntity(
        s"""
           |{
           |  "persistent": {
           |    "search.remote": {
           |      $remoteClustersConfigString
           |    }
           |  }
           |}
          """.stripMargin))
      request
    }

    val adminClient = container.adminClient
    Try(adminClient.execute(createRemoteClusterSettingsRequest(adminClient))).bracket { response =>
      response.getStatusLine.getStatusCode match {
        case 200 =>
        case _ =>
          throw new IllegalStateException("Cannot initialize remote cluster settings")
      }
    }
  }

}

trait ReadonlyRestEsClusterInitializer {
  def initialize(adminClient: RestClient, container: ReadonlyRestEsClusterContainer): Unit
}

object NoOpReadonlyRestEsClusterInitializer extends ReadonlyRestEsClusterInitializer {
  override def initialize(adminClient: RestClient, container: ReadonlyRestEsClusterContainer): Unit = ()
}

trait RemoteClustersInitializer {
  def remoteClustersConfiguration(localClusterRepresentatives: NonEmptyList[ReadonlyRestEsContainer]): Map[String, NonEmptyList[ReadonlyRestEsContainer]]
}