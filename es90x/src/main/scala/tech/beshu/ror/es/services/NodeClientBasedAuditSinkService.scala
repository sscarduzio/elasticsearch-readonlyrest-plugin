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

import cats.data.NonEmptyList
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.action.bulk.{BulkProcessor, BulkRequest, BulkResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.{ActionListener, DocWriteRequest}
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.common.BackoffPolicy
import org.elasticsearch.common.unit.{ByteSizeUnit, ByteSizeValue}
import org.elasticsearch.core.TimeValue
import org.elasticsearch.xcontent.XContentType
import tech.beshu.ror.accesscontrol.audit.sink.AuditDataStreamCreator
import tech.beshu.ror.accesscontrol.domain.{DataStreamName, IndexName}
import tech.beshu.ror.constants.{AUDIT_SINK_MAX_ITEMS, AUDIT_SINK_MAX_KB, AUDIT_SINK_MAX_RETRIES, AUDIT_SINK_MAX_SECONDS}
import tech.beshu.ror.es.utils.XContentJsonParserFactory
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, IndexBasedAuditSinkService}

import java.time.Clock
import java.util.function.BiConsumer

final class NodeClientBasedAuditSinkService(client: NodeClient,
                                            jsonParserFactory: XContentJsonParserFactory)
                                           (using Clock)
  extends IndexBasedAuditSinkService
    with DataStreamBasedAuditSinkService
    with RequestIdAwareLogging {

  private val bulkProcessor =
    BulkProcessor
      .builder(BulkRequestHandler, new AuditSinkBulkProcessorListener, "ror-audit-bulk-processor")
      .setBulkActions(AUDIT_SINK_MAX_ITEMS)
      .setBulkSize(ByteSizeValue.of(AUDIT_SINK_MAX_KB, ByteSizeUnit.KB))
      .setFlushInterval(TimeValue.timeValueSeconds(AUDIT_SINK_MAX_SECONDS))
      .setConcurrentRequests(1)
      .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), AUDIT_SINK_MAX_RETRIES))
      .build

  override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String): Unit = {
    submitDocument(indexName.name.value, documentId, jsonRecord)
  }

  override def submit(dataStreamName: DataStreamName.Full, documentId: String, jsonRecord: String): Unit = {
    submitDocument(dataStreamName.value.value, documentId, jsonRecord)
  }

  override def close(): Unit = {
    bulkProcessor.close()
  }

  private def submitDocument(indexName: String, documentId: String, jsonRecord: String): Unit = {
    bulkProcessor.add(
      new IndexRequest(indexName)
        .id(documentId)
        .source(jsonRecord, XContentType.JSON)
        .opType(DocWriteRequest.OpType.CREATE)
    )
  }

  private object BulkRequestHandler extends BiConsumer[BulkRequest, ActionListener[BulkResponse]] {
    override def accept(t: BulkRequest, u: ActionListener[BulkResponse]): Unit = client.bulk(t, u)
  }

  private class AuditSinkBulkProcessorListener extends BulkProcessor.Listener {
    override def beforeBulk(executionId: Long, request: BulkRequest): Unit = {
      noRequestIdLogger.debug(s"Flushing ${request.numberOfActions} bulk actions ...")
    }

    override def afterBulk(executionId: Long, request: BulkRequest, response: BulkResponse): Unit = {
      if (response.hasFailures) {
        noRequestIdLogger.error("Some failures flushing the BulkProcessor: ")
        response
          .getItems.to(LazyList)
          .filter(_.isFailed)
          .map(_.getFailureMessage)
          .groupBy(identity)
          .foreach { case (message, stream) =>
            noRequestIdLogger.error(s"${stream.size}x: $message")
          }
      }
    }

    override def afterBulk(executionId: Long, request: BulkRequest, failure: Throwable): Unit = {
      noRequestIdLogger.error(s"Failed flushing the BulkProcessor: ${failure.getMessage}", failure)
    }
  }

  override val dataStreamCreator: AuditDataStreamCreator =
    AuditDataStreamCreator(NonEmptyList.one(new EsDataStreamService(client, jsonParserFactory)))
}
