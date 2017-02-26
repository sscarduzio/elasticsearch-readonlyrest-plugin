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

package org.elasticsearch.plugin.readonlyrest;

import java.io.IOException;
import java.util.Arrays;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.wiring.ThreadRepo;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter {
    private final ThreadPool threadPool;
    private ClusterService clusterService;
    private ACL acl;
    private ConfigurationHelper conf;

    @Inject
    public IndexLevelActionFilter(Settings settings, ACL acl, ConfigurationHelper conf,
                                  ClusterService clusterService, ThreadPool threadPool) {
        super(settings);
        this.conf = conf;
        this.clusterService = clusterService;
        this.threadPool = threadPool;

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
    public <Request extends ActionRequest, Response extends ActionResponse> void apply(Task task,
                                                                                       String action,
                                                                                       Request request,
                                                                                       ActionListener<Response> listener,
                                                                                       ActionFilterChain<Request, Response> chain) {
        // Skip if disabled
        if (!conf.enabled) {
            chain.proceed(task, action, request, listener);
            return;
        }

        RestChannel channel = ThreadRepo.channel.get();
        boolean chanNull = channel == null;

        RestRequest req = null;
        if (!chanNull) {
            req = channel.request();
        }
        boolean reqNull = req == null;

        // This was not a REST message
        if (reqNull && chanNull) {
            chain.proceed(task, action, request, listener);
            return;
        }

        // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
        if (reqNull != chanNull) {
            if (chanNull)
                throw new SecurityPermissionException("Problems analyzing the channel object. " +
                        "Have you checked the security permissions?", null);
            if (reqNull)
                throw new SecurityPermissionException("Problems analyzing the request object. " +
                        "Have you checked the security permissions?", null);
        }

        RequestContext rc = new RequestContext(channel, req, action, request, clusterService, threadPool);
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
                            ActionListener<Response> searchListener = (ActionListener<Response>)
                                    new LoggerActionListener(action, request,(ActionListener<SearchResponse>)listener, rc); 
                            chain.proceed(task, action, request, searchListener);
                        }
                        else {
                            chain.proceed(task, action, request, listener);
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
        
        LoggerActionListener(String action, ActionRequest searchRequest,
                ActionListener<SearchResponse> baseListener,
                RequestContext requestContext) {
            this.action = action;
            this.searchRequest = (SearchRequest)searchRequest;
            this.baseListener = baseListener;
            this.requestContext = requestContext;
        }
        
        public void onResponse(SearchResponse searchResponse) {
            logger.info(
                    "search: {" +
                    "ACT:" + action +
                    ", USR:" + requestContext.getLoggedInUser() +
                    ", IDX:" + Arrays.toString(searchRequest.indices()) +
                    ", TYP:" + Arrays.toString(searchRequest.types()) +
                    ", SRC:" + convertToJson(searchRequest.source().buildAsBytes()) +
                    ", HIT:" + searchResponse.getHits().totalHits() +
                    ", RES:" + searchResponse.getHits().hits().length +
                    " }"
                    );
            
            baseListener.onResponse(searchResponse);
        }
        
        public void onFailure(Exception e) {
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
