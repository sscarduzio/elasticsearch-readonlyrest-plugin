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
import org.junit.runner.Description
import tech.beshu.ror.utils.elasticsearch.{IndexManager, LegacyTemplateManager, RorApiManager, SnapshotManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

object SingletonEsContainer
  extends EsClusterProvider
    with EsWithRorPluginContainerCreator
    with StrictLogging {

  private implicit val description: Description = Description.EMPTY

  val singleton: EsClusterContainer = createLocalClusterContainer(EsClusterSettings.basic)

  private lazy val adminClient = singleton.nodes.head.adminClient
  private lazy val indexManager = new IndexManager(adminClient, singleton.nodes.head.esVersion)
  private lazy val templateManager = new LegacyTemplateManager(adminClient, singleton.esVersion)
  private lazy val snapshotManager = new SnapshotManager(adminClient)
  private lazy val adminApiManager = new RorApiManager(adminClient, singleton.esVersion)

  logger.info("Starting singleton es container...")
  singleton.start()

  def cleanUpContainer(): Unit = {
    indexManager.removeAllIndices()
    templateManager.deleteAllTemplates()
    snapshotManager.deleteAllRepositories()
  }

  def updateConfig(rorConfigFileName: String): Unit = {
    adminApiManager.updateRorInIndexConfig(getResourceContent(rorConfigFileName)).force()
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer): Unit = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  final case class CouldNotUpdateRorConfigException() extends Exception("ROR config update using admin api failed")
}