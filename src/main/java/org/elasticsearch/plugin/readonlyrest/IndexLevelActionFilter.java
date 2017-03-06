/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;

import java.io.IOException;
import java.util.Arrays;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter implements ActionFilter {
  private ClusterService clusterService;
  private ACL acl;
  ESLogger logger =  Loggers.getLogger(this.getClass());
  private ConfigurationHelper conf;

  @Inject
  public IndexLevelActionFilter(Settings settings, ACL acl, ConfigurationHelper conf, ClusterService clusterService) {
    this.conf = conf;
    this.clusterService = clusterService;

    logger.info("Readonly REST plugin was loaded...");

    if (!conf.enabled) {
      logger.info("Readonly REST plugin is disabled!");
      return;
    }

    logger.info("Readonly REST plugin is enabled. Yay, ponies!");
    this.acl = acl;
  }

  @Override
  public int order() {
    return 0;
  }


  @Override
  public void apply(String action, ActionResponse actionResponse, ActionListener actionListener, ActionFilterChain actionFilterChain) {
      actionFilterChain.proceed(action,actionResponse,actionListener);
  }


  @Override
  public void apply(Task task, String action, ActionRequest actionRequest, ActionListener actionListener, ActionFilterChain chain) {

    // Skip if disabled
    if (!conf.enabled) {
      return;
    }

    final RestRequest req = actionRequest.getFromContext("request");
    final RestChannel channel = actionRequest.getFromContext("channel");

    boolean reqNull = req == null;
    boolean chanNull = channel == null;

    // This was not a REST message
    if (reqNull && chanNull) {
      return;
    }

    // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
    if (reqNull != chanNull) {
      if (chanNull)
        throw new SecurityPermissionException("Problems analyzing the channel object. Have you checked the security permissions?", null);
      if (reqNull)
        throw new SecurityPermissionException("Problems analyzing the request object. Have you checked the security permissions?", null);
    }

    RequestContext rc = new RequestContext(channel, req, action, actionRequest, clusterService);
    acl.check(rc)
      .exceptionally(throwable -> {
        logger.info("forbidden request: " + rc + " Reason: " + throwable.getMessage());
        sendNotAuthResponse(channel);
        return null;
      })
      .thenApply(result -> {
        if (result == null) return null;
        if (result.isMatch() && result.getBlock().getPolicy() == Block.Policy.ALLOW) {
          if (conf.searchLoggingEnabled && SearchAction.INSTANCE.name().equals(action)) {
            @SuppressWarnings("unchecked")
            ActionListener searchListener = (ActionListener)
              new LoggerActionListener(action,req, actionRequest,(ActionListener<SearchResponse>)actionListener, rc);
            chain.proceed(task, action, actionRequest, searchListener);
          }
          else {
            chain.proceed(task, action, actionRequest, actionListener);
          }
        } else {
          logger.info("forbidden request: " + rc + " Reason: " + result.getBlock() + " (" + result.getBlock() + ")");
          sendNotAuthResponse(channel);
        }
        return null;
      });

  }

  private void sendNotAuthResponse(RestChannel channel) {
    String reason = conf.forbiddenResponse;

    BytesRestResponse resp;
    if (acl.isBasicAuthConfigured()) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, reason);
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    } else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, reason);
    }

    channel.sendResponse(resp);
  }


  class LoggerActionListener implements ActionListener<SearchResponse> {
    private final String action;
    private final ActionListener<SearchResponse> baseListener;
    private final SearchRequest searchRequest;
    private final RequestContext requestContext;
    private final RestRequest req;

    LoggerActionListener(String action, RestRequest req, ActionRequest<?> searchRequest,
                         ActionListener<SearchResponse> baseListener,
                         RequestContext requestContext) {
      this.req = req;
      this.action = action;
      this.searchRequest = (SearchRequest)searchRequest;
      this.baseListener = baseListener;
      this.requestContext = requestContext;
    }

    public void onResponse(SearchResponse searchResponse) {
      logger.info(
        "search: {" +
          " ID:" + requestContext.getId() +
          ", ACT:" + action +
          ", USR:" + requestContext.getLoggedInUser() +
          ", IDX:" + Arrays.toString(searchRequest.indices()) +
          ", TYP:" + Arrays.toString(searchRequest.types()) +
          ", SRC:" + convertToJson(req.content()) +
          ", HIT:" + searchResponse.getHits().totalHits() +
          ", RES:" + searchResponse.getHits().hits().length +
          " }"
      );

      baseListener.onResponse(searchResponse);
    }

    public void onFailure(Throwable e) {
      baseListener.onFailure(e);
    }

    private String convertToJson(BytesReference searchSource) {
      if (searchSource != null)
        try {
          return XContentHelper.convertToJson(searchSource, true);
        }
        catch (IOException e) {
          logger.warn("Unable to convert searchSource to JSON", e);
        }
      return "";
    }



  }
}
