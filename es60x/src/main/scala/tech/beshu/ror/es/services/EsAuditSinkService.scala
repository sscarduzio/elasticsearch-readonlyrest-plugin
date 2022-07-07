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

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.bulk.{BackoffPolicy, BulkProcessor, BulkRequest, BulkResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.Client
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.unit.{ByteSizeUnit, ByteSizeValue, TimeValue}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.Constants.{AUDIT_SINK_MAX_ITEMS, AUDIT_SINK_MAX_KB, AUDIT_SINK_MAX_RETRIES, AUDIT_SINK_MAX_SECONDS}
import tech.beshu.ror.es.AuditSinkService

@Inject
class EsAuditSinkService(client: Client)
  extends AuditSinkService
    with Logging {

  private val bulkProcessor =
    BulkProcessor
      .builder(client, new AuditSinkBulkProcessorListener)
      .setBulkActions(AUDIT_SINK_MAX_ITEMS)
      .setBulkSize(new ByteSizeValue(AUDIT_SINK_MAX_KB.toInt, ByteSizeUnit.KB))
      .setFlushInterval(TimeValue.timeValueSeconds(AUDIT_SINK_MAX_SECONDS.toInt))
      .setConcurrentRequests(1)
      .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), AUDIT_SINK_MAX_RETRIES))
      .build

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    bulkProcessor.add(
      new IndexRequest(indexName)
        .`type`("ror_audit_evt")
        .id(documentId)
        .source(jsonRecord, XContentType.JSON)
    )
  }

  override def close(): Unit = {
    client.close()
  }

  private class AuditSinkBulkProcessorListener extends BulkProcessor.Listener {
    override def beforeBulk(executionId: Long, request: BulkRequest): Unit = {
      logger.debug(s"Flushing ${request.numberOfActions} bulk actions ...")
    }

    override def afterBulk(executionId: Long, request: BulkRequest, response: BulkResponse): Unit = {
      if (response.hasFailures) {
        logger.error("Some failures flushing the BulkProcessor: ")
        response
          .getItems.toStream
          .filter(_.isFailed)
          .map(_.getFailureMessage)
          .groupBy(identity)
          .foreach { case (message, stream) =>
            logger.error(stream.size + "x: " + message)
          }
      }
    }

    override def afterBulk(executionId: Long, request: BulkRequest, failure: Throwable): Unit = {
      logger.error(s"Failed flushing the BulkProcessor: ${failure.getMessage}", failure)
    }
  }

}
