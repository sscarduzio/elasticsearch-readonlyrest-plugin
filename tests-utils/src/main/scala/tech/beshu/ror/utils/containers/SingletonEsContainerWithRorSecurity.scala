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

import com.typesafe.scalalogging.StrictLogging
import tech.beshu.ror.utils.containers.SecurityType.{RorSecurity, RorWithXpackSecurity}
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch.{IndexManager, LegacyTemplateManager, RorApiManager, SnapshotManager}
import tech.beshu.ror.utils.misc.EsModulePatterns

import scala.util.{Failure, Success, Try}

object SingletonEsContainerWithRorSecurity
  extends EsClusterProvider
    with EsContainerCreator
    with StrictLogging
    with EsModulePatterns {

  val singleton: EsClusterContainer = createLocalClusterContainer(
    esNewerOrEqual63ClusterSettings = EsClusterSettings.create(
      clusterName = "ROR_SINGLE",
      securityType = RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default)
    ),
    esOlderThan63ClusterSettings =  EsClusterSettings.create(
      clusterName = "ROR_SINGLE",
      securityType = RorSecurity(ReadonlyRestPlugin.Config.Attributes.default)
    )
  )

  private lazy val adminClient = singleton.nodes.head.adminClient

  private lazy val indexManager = new IndexManager(adminClient, singleton.esVersion)
  private lazy val templateManager = new LegacyTemplateManager(adminClient, singleton.esVersion)
  private lazy val snapshotManager = new SnapshotManager(adminClient, singleton.esVersion)
  private lazy val rorApiManager = new RorApiManager(adminClient, singleton.esVersion)

  logger.info("Starting singleton es container...")
  singleton.start()

  def cleanUpContainer(): Unit = {
    logOnFailure(indexManager.removeAllIndices().force())
    logOnFailure(templateManager.deleteAllTemplates().force())
    logOnFailure(snapshotManager.deleteAllRepositories().force())
  }

  def updateConfig(rorConfig: String): Unit = {
    rorApiManager
      .updateRorInIndexConfig(rorConfig)
      .forceOKStatusOrConfigAlreadyLoaded()
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer): Unit = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  private def logOnFailure[A](action: => A): Unit = {
    Try(action) match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Error occurred while cleanup of singleton ES container: $ex")
    }
  }
}