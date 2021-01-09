/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import monix.execution.Scheduler
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import org.apache.logging.log4j.scala.Logging

class ProxyAuditSinkService(client: RestHighLevelClientAdapter)
                           (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    client
      .getIndex(new IndexRequest(indexName).id(documentId).source(jsonRecord, XContentType.JSON))
      .runAsync {
        case Right(resp) if resp.status().getStatus / 100 == 2 =>
        case Right(resp) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${resp.status().getStatus}")
        case Left(ex) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
      }
  }
}
