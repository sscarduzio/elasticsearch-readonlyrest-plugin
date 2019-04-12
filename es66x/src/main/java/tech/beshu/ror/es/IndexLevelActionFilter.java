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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.ACLHandler;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<ACL>> acl;
  private final AtomicReference<ESContext> context = new AtomicReference<>();
  private final LoggerShim loggerShim;
  private final IndexNameExpressionResolver indexResolver;
  private final Logger logger;

  public IndexLevelActionFilter(Settings settings,
      ClusterService clusterService,
      NodeClient client,
      ThreadPool threadPool,
      SettingsObservableImpl settingsObservable,
      Environment env
  ) {
    logger = LogManager.getLogger(this.getClass());
    loggerShim = ESContextImpl.mkLoggerShim(logger);

    BasicSettings baseSettings = BasicSettings.fromFileObj(loggerShim, env.configFile().toAbsolutePath(), settings);

    this.context.set(new ESContextImpl(client, baseSettings));

    this.clusterService = clusterService;
    this.indexResolver = new IndexNameExpressionResolver(settings);
    this.threadPool = threadPool;
    this.acl = new AtomicReference<>(Optional.empty());

    settingsObservable.addObserver((o, arg) -> {
      logger.info("Settings observer refreshing...");
      Environment newEnv = new Environment(settings, env.configFile().toAbsolutePath());
      BasicSettings newBasicSettings = new BasicSettings(settingsObservable.getCurrent(), newEnv.configFile().toAbsolutePath());
      ESContext newContext = new ESContextImpl(client, newBasicSettings);
      this.context.set(newContext);

      if (newContext.getSettings().isEnabled()) {
        try {
          ACL newAcl = new ACL(newContext);
          acl.set(Optional.of(newAcl));
          logger.info("Configuration reloaded - ReadonlyREST enabled");
        } catch (Exception ex) {
          logger.error("Cannot configure ReadonlyREST plugin", ex);
          throw ex;
        }
      }
      else {
        acl.set(Optional.empty());
        logger.info("Configuration reloaded - ReadonlyREST disabled");
      }
    });

    settingsObservable.forceRefresh();
    logger.info("Readonly REST plugin was loaded...");

    settingsObservable.pollForIndex(context.get());

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
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

      Optional<ACL> acl = this.acl.get();
      if (acl.isPresent()) {
        //  try(ThreadContext.StoredContext ctx = threadPool.getThreadContext().stashContext()) {
        handleRequest(acl.get(), task, action, request, listener, chain);
        //}
      }
      else {
        chain.proceed(task, action, request, listener);
      }
      return null;

    });
  }

  private <Request extends ActionRequest, Response extends ActionResponse>
  void handleRequest(ACL acl, Task task, String action, Request request, ActionListener<Response> listener, ActionFilterChain<Request, Response> chain) {
    RestChannel channel = ThreadRepo.channel.get();
    if (channel != null) {
      ThreadRepo.channel.remove();
    }

    boolean chanNull = channel == null;
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (ACL.shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }
    RequestInfo requestInfo = new RequestInfo(channel, task.getId(), action, request, clusterService, threadPool, context.get(), indexResolver);
    acl.check(requestInfo, new ACLHandler() {
      @Override
      public void onForbidden() {
        ElasticsearchStatusException exc = new ElasticsearchStatusException(
            context.get().getSettings().getForbiddenMessage(),
            acl.doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN
        ) {
          @Override
          public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("reason", context.get().getSettings().getForbiddenMessage());
            return builder;
          }
        };
        if (acl.doesRequirePassword()) {
          exc.addHeader("WWW-Authenticate", "Basic");
        }
        listener.onFailure(exc);
      }

      @Override
      public void onAllow(Object blockExitResult, Value.VariableResolver rc) {

        boolean hasProceeded = false;
        try {
          // Cache disabling for those 2 kind of request is crucial for
          // document level security to work. Otherwise we'd get an answer from
          // the cache some times and would not be filtered
          if (acl.involvesFilter()) {
            if (request instanceof SearchRequest) {
              logger.debug("ACL involves filters, will disable request cache for SearchRequest");

              ((SearchRequest) request).requestCache(Boolean.FALSE);
            }
            else if (request instanceof MultiSearchRequest) {
              logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest");
              for (SearchRequest sr : ((MultiSearchRequest) request).requests()) {
                sr.requestCache(Boolean.FALSE);
              }
            }
          }

          ResponseActionListener searchListener =
              new ResponseActionListener(action, request, (ActionListener<ActionResponse>) listener, (RequestContext) rc, logger);
          chain.proceed(task, action, request, (ActionListener<Response>) searchListener);

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
        listener.onFailure((ResourceNotFoundException) throwable.getCause());
      }

      @Override
      public void onErrored(Throwable t) {
        listener.onFailure((Exception) t);
      }
    });

  }

}
