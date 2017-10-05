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

package tech.beshu.ror.es;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import tech.beshu.ror.AuditSinkCore;
import tech.beshu.ror.commons.shims.ResponseContext;
import tech.beshu.ror.settings.RorSettings;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 14/06/2017.
 */

@Singleton
public class AuditSink extends AuditSinkCore {
  private static final Logger logger = Loggers.getLogger(AuditSink.class);
  private final static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
  private final BulkProcessor bulkProcessor;
  private final RorSettings settings;

  @Inject
  public AuditSink(Client client, RorSettings settings) {
    this.settings = settings;

    if (!isAuditCollectorEnabled()) {
      bulkProcessor = null;
      return;
    }

    this.bulkProcessor = BulkProcessor.builder(
      client,
      new BulkProcessor.Listener() {
        @Override
        public void beforeBulk(long executionId,
                               BulkRequest request) {
          logger.debug("Flushing " + request.numberOfActions() + " bulk actions..");
        }

        @Override
        public void afterBulk(long executionId,
                              BulkRequest request,
                              BulkResponse response) {
          if (response.hasFailures()) {
            logger.error("Some failures flushing the BulkProcessor: ");
            Arrays.stream(response.getItems())
              .filter(r -> r.isFailed())
              .map(r -> r.getFailureMessage())
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
              .forEach((message, times) -> logger.error(times + "x: " + message));
          }
        }

        @Override
        public void afterBulk(long executionId,
                              BulkRequest request,
                              Throwable failure) {
          logger.error("Failed flushing the BulkProcessor: " + failure.getMessage());
          failure.printStackTrace();
        }
      }
    )
      .setBulkActions(MAX_ITEMS)
      .setBulkSize(new ByteSizeValue(MAX_KB, ByteSizeUnit.KB))
      .setFlushInterval(TimeValue.timeValueSeconds(MAX_SECONDS))
      .setConcurrentRequests(1)
      .setBackoffPolicy(
        BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), MAX_RETRIES))
      .build();
  }

  public void submit(ResponseContext rc) throws JsonProcessingException {
    if (!isAuditCollectorEnabled()) {
      return;
    }
    String indexName = "readonlyrest_audit-" + formatter.format(Calendar.getInstance().getTime());
    IndexRequest ir = new IndexRequest(
      indexName,
      "ror_audit_evt",
      rc.getRequestContext().getId()
    ).source(
      rc.toJson(),
      XContentType.JSON
    );
    bulkProcessor.add(ir);
  }

  @Override
  public Boolean isAuditCollectorEnabled() {
    return settings.getAuditCollector();
  }
}
