/*
 *     Beshu Limited all rights reserved
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
