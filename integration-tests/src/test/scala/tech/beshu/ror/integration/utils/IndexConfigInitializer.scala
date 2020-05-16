package tech.beshu.ror.integration.utils

import org.apache.commons.lang.StringEscapeUtils.escapeJava
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.DocumentManagerJ
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class IndexConfigInitializer(readonlyrestIndexName: String, resourceFilePath: String)
  extends ElasticsearchNodeDataInitializer {

  private def insertInIndexConfig(documentManager: DocumentManagerJ, resourceFilePath: String): Unit = {
    documentManager.insertDocAndWaitForRefresh(
      s"/$readonlyrestIndexName/settings/1",
      s"""{"settings": "${escapeJava(getResourceContent(resourceFilePath))}"}"""
    )
  }

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    val documentManager = new DocumentManagerJ(adminRestClient)
    insertInIndexConfig(documentManager, resourceFilePath)
  }
}
