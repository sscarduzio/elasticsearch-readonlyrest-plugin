package org.elasticsearch.plugin.readonlyrest.es53x.actionlisteners;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;

import java.io.IOException;
import java.util.Arrays;

public class SearchlogSyncRuleActionListener extends RuleActionListener<SearchlogSyncRule> {

  private final Logger logger;

  public SearchlogSyncRuleActionListener(ESContext context) {
    super(SearchlogSyncRule.class);
    this.logger = context.logger(getClass());
  }

  @Override
  protected boolean onResponse(BlockExitResult result,
                               RequestContext rc,
                               ActionRequest ar,
                               ActionResponse response,
                               SearchlogSyncRule rule) {
    if (rule.shouldLog() && ar instanceof SearchRequest && response instanceof SearchResponse) {
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
  @Override
  protected boolean onFailure(BlockExitResult result,
                              RequestContext rc,
                              ActionRequest ar,
                              Exception e,
                              SearchlogSyncRule rule) {
    return true;
  }

  @SuppressWarnings("deprecation")
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
