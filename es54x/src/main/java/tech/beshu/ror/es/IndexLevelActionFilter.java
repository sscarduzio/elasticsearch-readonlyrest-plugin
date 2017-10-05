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

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.commons.BasicSettings;
import tech.beshu.ror.configuration.ReloadableSettings;
import tech.beshu.ror.es.actionlisteners.ACLActionListener;
import tech.beshu.ror.es.requestcontext.RequestContextImpl;
import tech.beshu.ror.settings.RorSettings;
import tech.beshu.ror.commons.shims.ACLHandler;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter, Consumer<BasicSettings> {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<ACL>> acl;
  private final ESContext context;
  private final ReloadableSettings reloadableSettings;
  private final NodeClient client;
  private final LoggerShim logger;
  private final IndexNameExpressionResolver indexResolver;

  @Inject
  public IndexLevelActionFilter(Settings settings,
                                ClusterService clusterService,
                                TransportService transportService,
                                NodeClient client,
                                ThreadPool threadPool,
                                ReloadableSettingsImpl reloadableSettings,
                                IndexNameExpressionResolver indexResolver
  )
    throws IOException {
    super(settings);
    this.context = new ESContextImpl();
    this.logger = context.logger(getClass());

    this.reloadableSettings = reloadableSettings;
    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
    this.threadPool = threadPool;
    this.acl = new AtomicReference<>(Optional.empty());
    this.client = client;
    this.reloadableSettings.addListener(this);

    new TaskManagerWrapper(settings).injectIntoTransportService(transportService, logger);

    logger.info("Readonly REST plugin was loaded...");
  }

  @Override
  public void accept(BasicSettings bsettings) {
    if (bsettings.isEnabled()) {
      try {
        AuditSinkImpl audit = new AuditSinkImpl(client, bsettings);
        ACL acl = new ACL(bsettings, this.context, audit);
        this.acl.set(Optional.of(acl));
        logger.info("Configuration reloaded - ReadonlyREST enabled");
      } catch (Exception ex) {
        logger.error("Cannot configure ReadonlyREST plugin", ex);
      }
    }
    else {
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
    }
    else {
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
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (ACL.shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }

    RequestContextImpl rc = new RequestContextImpl(channel.request(), action, request, clusterService, threadPool, context, indexResolver);

    acl.check(rc, new ACLHandler() {
      @Override
      public void onForbidden() {
        sendNotAuthResponse(channel, acl.getSettings());
      }

      @Override
      public void onAllow(Object blockExitResult) {
        boolean hasProceeded = false;
        try {
          @SuppressWarnings("unchecked")
          ActionListener<Response> aclActionListener = (ActionListener<Response>) new ACLActionListener(
            request, (ActionListener<ActionResponse>) listener, rc, blockExitResult, context, acl
          );
          chain.proceed(task, action, request, aclActionListener);
          hasProceeded = true;
          return;
        } catch (Throwable e) {
          e.printStackTrace();
        }
        if (!hasProceeded) {
          chain.proceed(task, action, request, listener);
        }
      }

      @Override
      public boolean isNotFound(Throwable throwable) {
        return throwable.getCause() instanceof ResourceNotFoundException;
      }

      @Override
      public void onNotFound(Throwable throwable) {
        sendNotFound((ResourceNotFoundException) throwable.getCause(), channel);
      }

      @Override
      public void onErrored(Throwable t) {
        sendNotAuthResponse(channel, acl.getSettings());
      }
    });

  }

  private void sendNotAuthResponse(RestChannel channel, RorSettings rorSettings) {
    BytesRestResponse resp;
    boolean doesRequirePassword = acl.get().map(ACL::doesRequirePassword).orElse(false);
    if (doesRequirePassword) {
      resp = new BytesRestResponse(RestStatus.UNAUTHORIZED, BytesRestResponse.TEXT_CONTENT_TYPE, rorSettings.getForbiddenMessage());
      logger.debug("Sending login prompt header...");
      resp.addHeader("WWW-Authenticate", "Basic");
    }
    else {
      resp = new BytesRestResponse(RestStatus.FORBIDDEN, BytesRestResponse.TEXT_CONTENT_TYPE, rorSettings.getForbiddenMessage());
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
      resp = new BytesRestResponse(RestStatus.NOT_FOUND, "application/json", b.string());
      channel.sendResponse(resp);
    } catch (Exception e1) {
      e1.printStackTrace();
    }
  }

}
