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
package tech.beshu.ror.es.request

import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.index.get.GetResult
import tech.beshu.ror.accesscontrol.domain.{DocumentId, DocumentWithIndex, IndexName}

object DocumentApiOps {

  object GetApi {

    //it's ugly but I don't know better way to do it
    def doesNotExistResponse(original: GetResponse) = {
      val exists = false
      val source = null
      val result = new GetResult(
        original.getIndex,
        original.getType,
        original.getId,
        original.getSeqNo,
        original.getPrimaryTerm,
        original.getVersion,
        exists,
        source,
        java.util.Collections.emptyMap(),
        java.util.Collections.emptyMap())
      new GetResponse(result)
    }

    implicit class GetResponseOps(val response: GetResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(response.getIndex, response.getId)
    }
  }

  object MultiGetApi {
    implicit class MultiGetItemResponseOps(val item: MultiGetItemResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(item.getIndex, item.getId)
    }
  }

  private def createDocumentWithIndex(indexStr: String, docId: String) = {
    val indexName = createIndexName(indexStr)
    val documentId = DocumentId(docId)
    DocumentWithIndex(indexName, documentId)
  }

  private def createIndexName(indexStr: String) = {
    IndexName
      .fromString(indexStr)
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }
}