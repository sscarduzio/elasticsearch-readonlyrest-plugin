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
package tech.beshu.ror.es.services

import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.{RequestOptions, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.es.AuditSinkService

class HighLevelClientAuditSinkService(client: RestHighLevelClient)
                                     (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    Task(client.index(new IndexRequest(indexName).id(documentId).source(jsonRecord, XContentType.JSON), RequestOptions.DEFAULT))
      .runAsync {
        case Right(resp) if resp.status().getStatus / 100 == 2 =>
        case Right(resp) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${resp.status().getStatus}")
        case Left(ex) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
      }
  }
}
