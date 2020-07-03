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
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.junit.runner.Description
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, IndexManager, SnapshotManager, TemplateManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

object SingletonEsContainer
  extends EsClusterProvider
    with EsWithRorPluginContainerCreator
    with StrictLogging {

  private implicit val description: Description = Description.EMPTY

  val singleton: EsClusterContainer = createLocalClusterContainer(EsClusterSettings.basic)

  private lazy val adminClient = singleton.nodes.head.adminClient
  private lazy val indexManager = new IndexManager(adminClient)
  private lazy val templateManager = new TemplateManager(adminClient)
  private lazy val snapshotManager = new SnapshotManager(adminClient)
  private lazy val adminApiManager = new ActionManagerJ(adminClient)

  logger.info("Starting singleton es container...")
  singleton.start()

  def cleanUpContainer(): Unit = {
    indexManager.removeAll
    templateManager.deleteAllTemplates()
    snapshotManager.deleteAllSnapshots()
  }

  def updateConfig(rorConfigFileName: String): Unit = {
    val response = adminApiManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent(rorConfigFileName))}"}"""
    )
    if (!response.isSuccess) {
      logger.error(s"Config update failed. Response: ${response.getBody}")
      throw CouldNotUpdateRorConfigException()
    }
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer): Unit = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  final case class CouldNotUpdateRorConfigException() extends Exception("ROR config update using admin api failed")
}