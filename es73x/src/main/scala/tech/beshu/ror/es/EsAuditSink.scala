package tech.beshu.ror.es

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.bulk.{BackoffPolicy, BulkProcessor, BulkRequest, BulkResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.Client
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.unit.{ByteSizeUnit, ByteSizeValue, TimeValue}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.Constants.{AUDIT_SINK_MAX_ITEMS, AUDIT_SINK_MAX_KB, AUDIT_SINK_MAX_RETRIES, AUDIT_SINK_MAX_SECONDS}

@Inject
class EsAuditSink(client: Client) extends AuditSink with Logging {

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
    bulkProcessor.add(new IndexRequest(indexName, "ror_audit_evt", documentId).source(jsonRecord, XContentType.JSON))
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
