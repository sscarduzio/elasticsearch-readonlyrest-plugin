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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.SearchlogRuleSettings;

import java.io.IOException;
import java.util.Arrays;

/**
 * Created by sscarduzio on 27/03/2017.
 */
public class SearchlogSyncRule extends SyncRule {

  private final Logger logger;
  private final boolean shouldLog;
  private final SearchlogRuleSettings settings;

  public SearchlogSyncRule(SearchlogRuleSettings s, ESContext context) {
    logger = context.logger(getClass());
    shouldLog = s.isEnabled();
    settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    return MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  @Override
  public boolean onResponse(BlockExitResult result, RequestContext rc, ActionRequest ar, ActionResponse response) {

    if (shouldLog && ar instanceof SearchRequest && response instanceof SearchResponse) {
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
  public boolean onFailure(BlockExitResult result, RequestContext rc, ActionRequest ar, Exception e) {
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
