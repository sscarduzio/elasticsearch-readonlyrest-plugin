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
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, IndexManagerJ}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

object SingletonEsContainer
  extends EsClusterProvider
    with EsWithRorPluginContainerCreator
    with StrictLogging {

  private implicit val description = Description.EMPTY

  val singleton = createLocalClusterContainer(EsClusterSettings.basic)

  lazy val adminClient = singleton.nodesContainers.head.adminClient
  private lazy val indexManager = new IndexManagerJ(adminClient)
  private lazy val adminApiManager = new ActionManagerJ(adminClient)

  logger.info("Starting singleton es container...")
  singleton.starting()

  def removeAllIndices() = {
    indexManager.removeAll()
  }

  def updateConfig(rorConfigFileName: String) = {
    val response = adminApiManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent(rorConfigFileName))}"}"""
    )
    if (!response.isSuccess) throw CouldNotUpdateRorConfigException()

    Thread.sleep(3000)
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer) = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }

  final case class CouldNotUpdateRorConfigException() extends Exception("ROR config update using admin api failed")
}