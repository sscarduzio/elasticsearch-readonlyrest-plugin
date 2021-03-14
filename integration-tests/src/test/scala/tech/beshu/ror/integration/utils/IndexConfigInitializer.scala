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
package tech.beshu.ror.integration.utils

import org.apache.commons.lang.StringEscapeUtils.escapeJava
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class IndexConfigInitializer(readonlyrestIndexName: String, resourceFilePath: String)
  extends ElasticsearchNodeDataInitializer {

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    insertInIndexConfig(documentManager, resourceFilePath)
  }

  private def insertInIndexConfig(documentManager: DocumentManager, resourceFilePath: String): Unit = {
    documentManager
      .createDoc(
        index = readonlyrestIndexName,
        id = 1,
        content = ujson.read(s"""{"settings": "${escapeJava(getResourceContent(resourceFilePath))}"}""")
      )
      .force()
  }
}
