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
import cats.implicits._
import com.dimafeng.testcontainers.{Container, SingleContainer}
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import org.junit.runner.Description
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.EsClusterContainer.{StartedClusterDependencies, StartedDependency}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._

import scala.language.existentials
import scala.util.Try

class EsClusterContainer private[containers](esClusterContainersCreators: NonEmptyList[StartedClusterDependencies => EsContainer],
                                             dependencies: List[DependencyDef],
                                             clusterInitializer: EsClusterInitializer) extends Container {

  private var clusterDependencies = StartedClusterDependencies(List.empty)
  private var createdContainers = List.empty[EsContainer]

  def esVersion: String = nodesContainers.head.esVersion

  def nodesContainers: NonEmptyList[EsContainer] = NonEmptyList.fromListUnsafe(createdContainers)

  def startedClusterDependencies: StartedClusterDependencies = clusterDependencies

  override def starting()(implicit description: Description): Unit = {
    val createdDependenciesContainers = dependencies.map(d => (d.name, d.containerCreator.apply(), d.originalPort))
    //starting dependencies...
    Task.gather(createdDependenciesContainers.map(s => Task(s._2.starting()(description)))).runSyncUnsafe()

    val justStartedClusterDependencies = StartedClusterDependencies(createdDependenciesContainers
      .map { case (name, container, port) => StartedDependency(name, container, port)})
    clusterDependencies = justStartedClusterDependencies

    val justCreatedContainers = esClusterContainersCreators.toList.map(creator => creator(justStartedClusterDependencies))
    createdContainers = justCreatedContainers

    //starting es containers...
    Task.gather(justCreatedContainers.map(s => Task(s.starting()(description)))).runSyncUnsafe()
    clusterInitializer.initialize(createdContainers.head.adminClient, this)
  }

  override def finished()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.finished()(description))

  override def succeeded()(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.succeeded()(description))

  override def failed(e: Throwable)(implicit description: Description): Unit =
    nodesContainers.toList.foreach(_.failed(e)(description))

}

object EsClusterContainer {
  final case class StartedDependency(name: String, container: SingleContainer[GenericContainer[_]], originalPort: Int)
  final case class StartedClusterDependencies(values: List[StartedDependency])
}

class EsRemoteClustersContainer private[containers](val localClusters: NonEmptyList[EsClusterContainer],
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

  private def remoteClustersInitializer(container: EsContainer,
                                        remoteClustersConfig: Map[String, NonEmptyList[EsContainer]]): Unit = {
    def createRemoteClusterSettingsRequest(esClient: RestClient) = {
      val remoteClustersConfigString = remoteClustersConfig
        .map { case (name, remoteClusterContainers) =>
          s""""$name": { "seeds": [ ${remoteClusterContainers.toList.map(c => s""""${c.name}:9300"""").mkString(",")} ] }"""
        }
        .mkString(",\n")

      val request = new HttpPut(esClient.from("_cluster/settings"))
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

    val esClient = container.adminClient
    Try(esClient.execute(createRemoteClusterSettingsRequest(esClient))).bracket { response =>
      response.getStatusLine.getStatusCode match {
        case 200 =>
        case _ =>
          throw new IllegalStateException("Cannot initialize remote cluster settings")
      }
    }
  }

}

trait EsClusterInitializer {
  def initialize(esClient: RestClient, container: EsClusterContainer): Unit
}

object NoOpEsClusterInitializer extends EsClusterInitializer {
  override def initialize(esClient: RestClient, container: EsClusterContainer): Unit = ()
}

trait RemoteClustersInitializer {
  def remoteClustersConfiguration(localClusterRepresentatives: NonEmptyList[EsContainer]): Map[String, NonEmptyList[EsContainer]]
}

final case class EsClusterSettings(name: String,
                                   numberOfInstances: Int = 1,
                                   nodeDataInitializer: ElasticsearchNodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
                                   clusterInitializer: EsClusterInitializer = NoOpEsClusterInitializer,
                                   dependentServicesContainers: List[DependencyDef] = Nil,
                                   xPackSupport: Boolean = false,
                                   configHotReloadingEnabled: Boolean = true,
                                   customRorIndexName: Option[String] = None,
                                   internodeSslEnabled: Boolean = false)(implicit val rorConfigFileName: String)

final case class DependencyDef(name: String, containerCreator: Coeval[SingleContainer[GenericContainer[_]]], originalPort: Int)



