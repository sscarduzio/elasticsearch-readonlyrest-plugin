package tech.beshu.ror.integration.plugin

import org.apache.logging.log4j.scala.Logging
import org.junit.runner.Description
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.utils.containers.{EsClusterProvider, EsWithRorPluginContainerCreator}
import tech.beshu.ror.utils.elasticsearch.IndexManagerJ

object SingletonEsContainer
  extends EsClusterProvider
    with EsWithRorPluginContainerCreator
    with Logging {

  private implicit val description = Description.EMPTY

  val singleton = createLocalClusterContainer(BasicSingleNodeEsClusterSupport.basicEsSettings)

  logger.info("Starting singleton es container with installed plugin")
  singleton.starting()

  val adminClient = singleton.nodesContainers.head.adminClient

  private lazy val indexManager = new IndexManagerJ(adminClient)

  def removeAllIndices() = {
    indexManager.removeAll()
  }
}