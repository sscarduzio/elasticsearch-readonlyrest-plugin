package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;

import java.io.IOException;
import java.util.Arrays;

/**
 * Created by sscarduzio on 27/03/2017.
 */
public class SearchlogSyncRule extends SyncRule {
  private final Logger logger = Loggers.getLogger(getClass());

  private boolean shouldLog = false;

  public SearchlogSyncRule(Settings s) throws RuleNotConfiguredException {
    try {
      shouldLog = s.getAsBoolean(getKey(), false);
      if (!shouldLog) throw new RuleNotConfiguredException();
    } catch (Exception e) {
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    return MATCH;
  }

  @Override
  public boolean onResponse(RequestContext rc, ActionRequest ar, ActionResponse response) {
    if (ar instanceof SearchRequest && response instanceof SearchResponse) {
      SearchRequest searchRequest = (SearchRequest) ar;
      SearchResponse searchResponse = (SearchResponse) response;
      logger.info(
        "search: {" +
          " ID:" + rc.getId() +
          ", ACT:" + rc.getAction() +
          ", USR:" + rc.getLoggedInUser() +
          ", IDX:" + Arrays.toString(searchRequest.indices()) +
          ", TYP:" + Arrays.toString(searchRequest.types()) +
          ", SRC:" + convertToJson(searchRequest.source().buildAsBytes()) +
          ", HIT:" + searchResponse.getHits().totalHits() +
          ", RES:" + searchResponse.getHits().hits().length +
          " }"
      );
    }
    return true;
  }

  private String convertToJson(BytesReference searchSource) {
    if (searchSource != null)
      try {
        return XContentHelper.convertToJson(searchSource, true);
      } catch (IOException e) {
        logger.warn("Unable to convert searchSource to JSON", e);
      }
    return "";
  }
}
