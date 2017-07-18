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

import com.google.common.collect.Sets;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;
import org.elasticsearch.search.SearchHit;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;

public class IndicesRewriteSyncRuleActionListener extends RuleActionListener<IndicesRewriteSyncRule> {

  private final LoggerShim logger;

  public IndicesRewriteSyncRuleActionListener(ESContext context) {
    super(IndicesRewriteSyncRule.class);
    this.logger = context.logger(getClass());
  }

  @Override
  protected boolean onResponse(BlockExitResult result,
                               RequestContext rc,
                               ActionRequest ar,
                               ActionResponse response,
                               IndicesRewriteSyncRule rule) {
    // #TODO rewrite response for MultiGet, MultiSearch, Bulk
    if (response instanceof SearchResponse) {
      handleSearchResponse((SearchResponse) response, rc);
    }
    if (response instanceof MultiSearchResponse) {
      MultiSearchResponse msr = (MultiSearchResponse) response;
      for (MultiSearchResponse.Item i : msr.getResponses()) {
        if (!i.isFailure()) {
          handleSearchResponse(i.getResponse(), rc);
        }
        // #TODO Maybe do something with the failure message?
      }
    }
    if (response instanceof GetResponse) {
      handleGetResponse((GetResponse) response, rc);
    }
    if (response instanceof MultiGetResponse) {
      MultiGetResponse mgr = (MultiGetResponse) response;
      for (MultiGetItemResponse i : mgr.getResponses()) {
        if (!i.isFailed()) {
          handleGetResponse(i.getResponse(), rc);
        }
      }
    }

//    if (response instanceof BulkShardResponse) {
//      BulkShardResponse bsr = (BulkShardResponse) response;
//      final Set<String> originalIndex = Sets.newHashSet(rc.getIndices().iterator().next());
//      ReflecUtils.setIndices(bsr.getShardId().getIndex(), Sets.newHashSet("name"), originalIndex, logger);
//      for (BulkItemResponse i : bsr.getResponses()) {
//        if (!i.isFailed()) {
//          ReflecUtils.setIndices(
//              i.getResponse().getShardId().getIndex(),
//              Sets.newHashSet("name"),
//              originalIndex,
//              logger
//          );
//        }
//      }
//    }

    return true;
  }

  @Override
  protected boolean onFailure(BlockExitResult result,
                              RequestContext rc,
                              ActionRequest ar,
                              Throwable e,
                              IndicesRewriteSyncRule rule) {
    if (e instanceof IndexNotFoundException) {
      ((IndexNotFoundException) e).setIndex(rc.getIndices().iterator().next());
    }
    if (e instanceof ResourceNotFoundException) {
      ((ResourceNotFoundException) e).addHeader("es.resource.id", rc.getIndices().iterator().next());
    }
    return true;
  }

  // Translate the search results indices
  private void handleSearchResponse(SearchResponse sr, RequestContext rc) {
    for (SearchHit h : sr.getHits().getHits()) {
      ReflecUtils.setIndices(h, Sets.newHashSet("index"),
                             Sets.newHashSet(rc.getIndices().iterator().next()), logger
      );
    }
  }

  // Translate the get results indices
  private void handleGetResponse(GetResponse gr, RequestContext rc) {
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        Field f = GetResponse.class.getDeclaredField("getResult");
        f.setAccessible(true);
        GetResult getResult = (GetResult) f.get(gr);

        f = GetResult.class.getDeclaredField("index");
        f.setAccessible(true);
        f.set(getResult, rc.getIndices().iterator().next());

      } catch (NoSuchFieldException | IllegalAccessException e) {
        e.printStackTrace();
      }

      return null;
    });
  }

}
