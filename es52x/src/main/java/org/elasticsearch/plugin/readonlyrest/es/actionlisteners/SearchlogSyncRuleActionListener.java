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
package org.elasticsearch.plugin.readonlyrest.es.actionlisteners;

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
