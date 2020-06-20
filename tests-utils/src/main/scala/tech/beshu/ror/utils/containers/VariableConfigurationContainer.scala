package tech.beshu.ror.utils.containers

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.junit.runner.Description
import tech.beshu.ror.utils.containers.VariableConfigurationContainer.CouldNotUpdateRorConfigException
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, IndexManager, SnapshotManager, TemplateManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class VariableConfigurationContainer(container: EsClusterContainer) extends StrictLogging {
  private implicit val description: Description = Description.EMPTY //TODO: is it required?
  private lazy val adminClient = container.nodes.head.adminClient
  private lazy val indexManager = new IndexManager(adminClient)
  private lazy val templateManager = new TemplateManager(adminClient)
  private lazy val snapshotManager = new SnapshotManager(adminClient)
  private lazy val adminApiManager = new ActionManagerJ(adminClient)

  def start(): Unit = container.start()

  def clean(): Unit = {
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
    nodeDataInitializer.initialize(container.esVersion, adminClient)
  }
}
object VariableConfigurationContainer {
  final case class CouldNotUpdateRorConfigException() extends Exception("ROR config update using admin api failed")
}
