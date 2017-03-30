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

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
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
  private ConfigurationHelper conf;

  @Inject
  public IndexLevelActionFilter(Settings settings, ConfigurationHelper conf,
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
    conf.acl.check(rc)
      .exceptionally(throwable -> {
        logger.info("forbidden request: " + rc + " Reason: " + throwable.getMessage());
        throwable.printStackTrace();
        sendNotAuthResponse(channel);
        return null;
      })
      .thenApply(result -> {
        if (result == null) return null;

        if (result.isMatch() && result.getBlock().getPolicy() == Block.Policy.ALLOW) {

          try {
            @SuppressWarnings("unchecked")
            ActionListener<Response> aclActionListener =
              (ActionListener<Response>) new ACLActionListener(request, (ActionListener<ActionResponse>) listener, rc, result);
            chain.proceed(task, action, request, aclActionListener);
            return null;
          } catch (Throwable e) {
            e.printStackTrace();
          }

          chain.proceed(task, action, request, listener);
          return null;
        }

        logger.info("forbidden request: " + rc + " Reason: " + result.getBlock() + " (" + result.getBlock() + ")");
        sendNotAuthResponse(channel);
        return null;
      });
  }

  @Override
  public <Response extends ActionResponse> void apply(String action, Response response, ActionListener<Response> listener, ActionFilterChain<?, Response> chain) {
    chain.proceed(action, response, listener);
  }

  private void sendNotAuthResponse(RestChannel channel) {
    String reason = conf.forbiddenResponse;

    BytesRestResponse resp;
    if (conf.acl.isBasicAuthConfigured()) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, reason);
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    }
    else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, reason);
    }

    channel.sendResponse(resp);
  }

}
