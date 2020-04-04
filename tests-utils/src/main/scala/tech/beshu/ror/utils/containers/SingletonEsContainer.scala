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
    adminApiManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent(rorConfigFileName))}"}"""
    )
  }

  def initNode(nodeDataInitializer: ElasticsearchNodeDataInitializer) = {
    nodeDataInitializer.initialize(singleton.esVersion, adminClient)
  }
}