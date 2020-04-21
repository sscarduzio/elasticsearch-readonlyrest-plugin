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
package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import cats.implicits._
import com.dimafeng.testcontainers.{Container, GenericContainer}
import monix.eval.Coeval
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils._

import scala.language.existentials
import scala.util.Try

class EsClusterContainer private[containers](val nodes: NonEmptyList[EsContainer],
                                             dependencies: List[DependencyDef]) extends Container {

  val depsContainers: List[(DependencyDef, GenericContainer)] =
    dependencies.map(d => (d, d.containerCreator.apply()))

  val esVersion: String = nodes.head.esVersion

  override def start(): Unit = {
    depsContainers.foreach(_._2.start())
    nodes.toList.foreach(_.start())
  }

  override def stop(): Unit = {
    nodes.toList.foreach(_.stop())
    depsContainers.foreach(_._2.stop())
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
    def createRemoteClusterSettingsRequest(esClient: RestClient) = {
      val remoteClustersConfigString = remoteClustersConfig
        .map { case (name, remoteCluster) =>
          s""""$name": { "seeds": [ ${remoteCluster.nodes.toList.map(c => s""""${c.name}:9300"""").mkString(",")} ] }"""
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

    val esClient = container.nodes.head.adminClient
    Try(esClient.execute(createRemoteClusterSettingsRequest(esClient))).bracket { response =>
      response.getStatusLine.getStatusCode match {
        case 200 =>
        case _ =>
          throw new IllegalStateException("Cannot initialize remote cluster settings")
      }
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
                                   xPackSupport: Boolean = false,
                                   configHotReloadingEnabled: Boolean = true,
                                   customRorIndexName: Option[String] = None,
                                   internodeSslEnabled: Boolean = false)(implicit val rorConfigFileName: String)

final case class DependencyDef(name: String, containerCreator: Coeval[GenericContainer])
