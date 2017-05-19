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

package org.elasticsearch.plugin.readonlyrest.es;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.BlockPolicy;
import org.elasticsearch.plugin.readonlyrest.configuration.ReloadableSettings;
import org.elasticsearch.plugin.readonlyrest.es.actionlisteners.ACLActionListener;
import org.elasticsearch.plugin.readonlyrest.es.actionlisteners.RuleActionListenersProvider;
import org.elasticsearch.plugin.readonlyrest.es.requestcontext.RequestContextImpl;
import org.elasticsearch.plugin.readonlyrest.settings.RorSettings;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.elasticsearch.rest.RestStatus.FORBIDDEN;
import static org.elasticsearch.rest.RestStatus.NOT_FOUND;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter, Consumer<RorSettings> {

  private final ThreadPool threadPool;
  private final IndexNameExpressionResolver indexResolver;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<ACL>> acl;
  private final ESContext context;
  private final RuleActionListenersProvider ruleActionListenersProvider;
  private final ReloadableSettings reloadableSettings;
  private final Client client;

  @Inject
  public IndexLevelActionFilter(Settings settings, ReloadableSettingsImpl reloadableConfiguration,
                                Client client, ClusterService clusterService, ThreadPool threadPool,
                                IndexNameExpressionResolver indexResolver) throws IOException {
    super(settings);
    this.reloadableSettings = reloadableConfiguration;
    this.client = client;
    this.context = new ESContextImpl();
    this.clusterService = clusterService;
    this.threadPool = threadPool;
    this.indexResolver = indexResolver;
    this.acl = new AtomicReference<>(Optional.empty());
    this.ruleActionListenersProvider =  new RuleActionListenersProvider(context);

    this.reloadableSettings.addListener(this);
    scheduleConfigurationReload();
    logger.info("Readonly REST plugin was loaded...");
  }

  @Override
  public void accept(RorSettings rorSettings) {
    if (rorSettings.isEnabled()) {
      try {
        ACL acl = new ACL(rorSettings, this.context);
        this.acl.set(Optional.of(acl));
        logger.info("Configuration reloaded - ReadonlyREST enabled");
      } catch (Exception ex) {
        logger.error("Cannot configure ReadonlyREST plugin", ex);
      }
    } else {
      this.acl.set(Optional.empty());
      logger.info("Configuration reloaded - ReadonlyREST disabled");
    }
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
    Optional<ACL> acl = this.acl.get();
    if (acl.isPresent()) {
      handleRequest(acl.get(), task, action, request, listener, chain);
    } else {
      chain.proceed(task, action, request, listener);
    }
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(ACL acl,
                                                                                              Task task,
                                                                                              String action,
                                                                                              Request request,
                                                                                              ActionListener<Response> listener,
                                                                                              ActionFilterChain<Request, Response> chain) {
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
      if (chanNull) {
        throw new SecurityPermissionException("Problems analyzing the channel object. " +
            "Have you checked the security permissions?", null);
      }
      if (reqNull) {
        throw new SecurityPermissionException("Problems analyzing the request object. " +
            "Have you checked the security permissions?", null);
      }
    }

    RequestContextImpl rc = new RequestContextImpl(req, action, request, clusterService, indexResolver, threadPool, context);

    acl.check(rc)
        .exceptionally(throwable -> {
          logger.info("forbidden request: " + rc + " Reason: " + throwable.getMessage());
          if (throwable.getCause() instanceof ResourceNotFoundException) {
            logger.warn("Resource not found! ID: " + rc.getId() + "  " + throwable.getCause().getMessage());
            sendNotFound((ResourceNotFoundException) throwable.getCause(), channel);
            return null;
          }
          throwable.printStackTrace();
          sendNotAuthResponse(channel, acl.getSettings());
          return null;
        })
        .thenApply(result -> {
          assert result != null;

          if (result.isMatch() && BlockPolicy.ALLOW.equals(result.getBlock().getPolicy())) {
            try {
              @SuppressWarnings("unchecked")
              ActionListener<Response> aclActionListener = (ActionListener<Response>) new ACLActionListener(
                  request, (ActionListener<ActionResponse>) listener, ruleActionListenersProvider, rc, result, context
              );
              chain.proceed(task, action, request, aclActionListener);
              return null;
            } catch (Throwable e) {
              e.printStackTrace();
            }

            chain.proceed(task, action, request, listener);
            return null;
          }

          logger.info("forbidden request: " + rc + " Reason: " + result.getBlock() + " (" + result.getBlock() + ")");
          sendNotAuthResponse(channel, acl.getSettings());
          return null;
        });
  }

  private void sendNotAuthResponse(RestChannel channel, RorSettings rorSettings) {
    BytesRestResponse resp;
    boolean doesRequirePassword = acl.get().map(ACL::doesRequirePassword).orElse(false) && rorSettings.isPromptForBasicAuth();
    if (doesRequirePassword) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, BytesRestResponse.TEXT_CONTENT_TYPE, rorSettings.getForbiddenMessage());
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    } else {
      resp = new BytesRestResponse(FORBIDDEN, BytesRestResponse.TEXT_CONTENT_TYPE, rorSettings.getForbiddenMessage());
    }

    channel.sendResponse(resp);
  }

  private void sendNotFound(ResourceNotFoundException e, RestChannel channel) {
    try {
      XContentBuilder b = JsonXContent.contentBuilder();
      b.startObject();
      ElasticsearchException.generateFailureXContent(b, ToXContent.EMPTY_PARAMS, e, true);
      b.endObject();
      BytesRestResponse resp;
      resp = new BytesRestResponse(NOT_FOUND, "application/json", b.string());
      channel.sendResponse(resp);
    } catch (Exception e1) {
      e1.printStackTrace();
    }
  }

  private void scheduleConfigurationReload() {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    Runnable task = new Runnable() {
      @Override
      public void run() {
        try {
          logger.debug("[CLUSTERWIDE SETTINGS] checking index..");
          reloadableSettings.reload(new ESClientSettingsContentProvider(client));
          logger.info("Cluster-wide settings found, overriding elasticsearch.yml");
          executor.shutdown();
        } catch (ElasticsearchException ee) {
          logger.info("[CLUSTERWIDE SETTINGS] settings not found, please install ReadonlyREST Kibana plugin." +
              " Will keep on using elasticearch.yml.");
          executor.shutdown();
        } catch (Throwable t) {
          logger.debug("[CLUSTERWIDE SETTINGS] index not ready yet..");
          executor.schedule(this, 200, TimeUnit.MILLISECONDS);
        }
      }
    };
    executor.schedule(task, 200, TimeUnit.MILLISECONDS);
  }
}
