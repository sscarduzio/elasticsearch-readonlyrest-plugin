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

import monix.execution.Scheduler$;
import monix.execution.schedulers.CanBlock$;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import scala.Function1;
import scala.collection.JavaConverters$;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.util.Either;
import tech.beshu.ror.SecurityPermissionException;
import tech.beshu.ror.acl.AclHandlingResult;
import tech.beshu.ror.acl.AclStaticContext;
import tech.beshu.ror.acl.blocks.BlockContext;
import tech.beshu.ror.acl.AclActionHandler;
import tech.beshu.ror.acl.AclResultCommitter;
import tech.beshu.ror.acl.BlockContextJavaHelper$;
import tech.beshu.ror.boot.RorEngineFactory;
import tech.beshu.ror.boot.RorEngineFactory$;
import tech.beshu.ror.acl.request.EsRequestContext;
import tech.beshu.ror.acl.request.RequestContext;
import tech.beshu.ror.utils.ScalaJavaHelper$;
import tech.beshu.ror.settings.__old_BasicSettings;
import tech.beshu.ror.shims.es.ESContext;
import tech.beshu.ror.shims.es.LoggerShim;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter extends AbstractComponent implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<RorEngineFactory.__old_Engine>> rorEngine;
  private final AtomicReference<ESContext> context = new AtomicReference<>();
  private final LoggerShim loggerShim;

  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  @Inject
  public IndexLevelActionFilter(Settings settings,
      ClusterService clusterService,
      NodeClient client,
      ThreadPool threadPool,
      SettingsObservableImpl settingsObservable
  ) {
    super(settings);
    this.loggerShim = ESContextImpl.mkLoggerShim(logger);

    Environment env = new Environment(settings);
    __old_BasicSettings baseSettings = __old_BasicSettings.fromFile(loggerShim, env.configFile().toAbsolutePath(), settings.getAsStructuredMap());

    this.context.set(new ESContextImpl(baseSettings));

    this.clusterService = clusterService;
    this.threadPool = threadPool;
    this.rorEngine = new AtomicReference<>(Optional.empty());

    settingsObservable.addObserver((o, arg) -> {
      logger.info("Settings observer refreshing...");
      Environment newEnv = new Environment(settings);
      __old_BasicSettings newBasicSettings = new __old_BasicSettings(settingsObservable.getCurrent(),
          newEnv.configFile().toAbsolutePath());
      ESContext newContext = new ESContextImpl(newBasicSettings);
      this.context.set(newContext);

      if (newContext.getSettings().isEnabled()) {
        FiniteDuration timeout = FiniteDuration.apply(30, TimeUnit.SECONDS);
        RorEngineFactory.__old_Engine engine = AccessController.doPrivileged((PrivilegedAction<RorEngineFactory.__old_Engine>) () ->
            RorEngineFactory$.MODULE$.reload(
                createAuditSink(client, newBasicSettings),
                newContext.getSettings().getRaw().yaml()).runSyncUnsafe(timeout, Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit()
            )
        );
        Optional<RorEngineFactory.__old_Engine> oldEngine = rorEngine.getAndSet(Optional.of(engine));
        oldEngine.ifPresent(scheduleDelayedEngineShutdown(Duration.ofSeconds(10)));
        logger.info("Configuration reloaded - ReadonlyREST enabled");
      }
      else {
        Optional<RorEngineFactory.__old_Engine> oldEngine = rorEngine.getAndSet(Optional.empty());
        oldEngine.ifPresent(scheduleDelayedEngineShutdown(Duration.ofSeconds(10)));
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
  public <Response extends ActionResponse> void apply(String action, Response response, ActionListener<Response> listener,
      ActionFilterChain<?, Response> chain) {
    chain.proceed(action, response, listener);
  }

  @Override
  public <Request extends ActionRequest, Response extends ActionResponse> void apply(Task task,
      String action,
      Request request,
      ActionListener<Response> listener,
      ActionFilterChain<Request, Response> chain) {
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      Optional<RorEngineFactory.__old_Engine> engine = this.rorEngine.get();
      if (engine.isPresent()) {
        handleRequest(engine.get(), task, action, request, listener, chain);
      }
      else {
        chain.proceed(task, action, request, listener);
      }
      return null;
    });
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(
      RorEngineFactory.__old_Engine engine,
      Task task,
      String action,
      Request request,
      ActionListener<Response> listener,
      ActionFilterChain<Request, Response> chain
  ) {
    RestChannel channel = ThreadRepo.channel.get();
    if (channel != null) {
      ThreadRepo.channel.remove();
    }

    boolean chanNull = channel == null;
    boolean reqNull = channel == null ? true : channel.request() == null;
    if (shouldSkipACL(chanNull, reqNull)) {
      chain.proceed(task, action, request, listener);
      return;
    }
    RequestInfo requestInfo = new RequestInfo(channel, task.getId(), action, request, clusterService, threadPool);
    RequestContext requestContext = requestContextFrom(requestInfo);

    Consumer<ActionListener<Response>> proceed = responseActionListener -> {
      chain.proceed(task, action, request, responseActionListener);
    };

    engine.acl().handle(requestContext).runAsync(
        handleAclResult(engine, listener, request, requestContext, requestInfo, proceed), Scheduler$.MODULE$.global());
  }

  private <Request extends ActionRequest, Response extends ActionResponse> Function1<Either<Throwable, AclHandlingResult>, BoxedUnit> handleAclResult(
      RorEngineFactory.__old_Engine engine,
      ActionListener<Response> listener,
      Request request,
      RequestContext requestContext,
      RequestInfo requestInfo,
      Consumer<ActionListener<Response>> chainProceed
  ) {
    return result -> {
      try (ThreadContext.StoredContext ignored = threadPool.getThreadContext().stashContext()) {
        if(result.isRight()) {
          AclActionHandler handler = createAclActionHandler(engine.context(), requestInfo, request, requestContext, listener, chainProceed);
          AclResultCommitter.commit(result.right().get(), handler);
        } else {
          listener.onFailure(new Exception(result.left().get()));
        }
      }
      return null;
    };
  }

  private <Request extends ActionRequest, Response extends ActionResponse> AclActionHandler createAclActionHandler(
      AclStaticContext aclStaticContext,
      RequestInfo requestInfo,
      Request request,
      RequestContext requestContext,
      ActionListener<Response> baseListener,
      Consumer<ActionListener<Response>> chainProceed
  ) {
    return new AclActionHandler() {
      @Override
      public void onAllow(BlockContext blockContext) {
        try {
          ActionListener<Response> searchListener = createSearchListener(baseListener, request, requestContext,
              blockContext);
          requestInfo.writeResponseHeaders(JavaConverters$.MODULE$.mapAsJavaMap(BlockContextJavaHelper$.MODULE$.responseHeadersFrom(blockContext)));
          requestInfo.writeToThreadContextHeaders(JavaConverters$.MODULE$.mapAsJavaMap(BlockContextJavaHelper$.MODULE$.contextHeadersFrom(blockContext)));
          requestInfo.writeIndices(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.indicesFrom(blockContext)));
          requestInfo.writeSnapshots(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.snapshotsFrom(blockContext)));
          requestInfo.writeRepositories(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.repositoriesFrom(blockContext)));

          chainProceed.accept(searchListener);
        } catch (Throwable e) {
          e.printStackTrace();
          chainProceed.accept(baseListener);
        }
      }

      private ActionListener<Response> createSearchListener(ActionListener<Response> listener,
          Request request, RequestContext requestContext, BlockContext blockContext) {
        try {
          // Cache disabling for those 2 kind of request is crucial for
          // document level security to work. Otherwise we'd get an answer from
          // the cache some times and would not be filtered
          if (aclStaticContext.involvesFilter()) {
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

          return (ActionListener<Response>) new ResponseActionListener((ActionListener<ActionResponse>) listener,
              requestContext, blockContext);
        } catch (Throwable e) {
          logger.error("on allow exception", e);
          return listener;
        }
      }

      @Override
      public void onForbidden() {
        ElasticsearchStatusException exc = new ElasticsearchStatusException(
            context.get().getSettings().getForbiddenMessage(),
            aclStaticContext.doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN) {
          @Override
          public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("reason", context.get().getSettings().getForbiddenMessage());
            return builder;
          }
        };
        if (aclStaticContext.doesRequirePassword()) {
          exc.addHeader("WWW-Authenticate", "Basic");
        }
        baseListener.onFailure(exc);
      }

      @Override
      public void onError(Throwable t) {
        baseListener.onFailure((Exception) t);
      }

      @Override
      public void onPassThrough() {
        chainProceed.accept(baseListener);
      }
    };
  }

  private AuditSink createAuditSink(Client client, __old_BasicSettings settings) {
    return new AuditSink() {
      AuditSinkImpl auditSink = new AuditSinkImpl(client, settings);

      @Override
      public void submit(String indexName, String documentId, String jsonRecord) {
        auditSink.submit(indexName, documentId, jsonRecord);
      }
    };
  }

  private RequestContext requestContextFrom(RequestInfo requestInfo) {
    try {
      return ScalaJavaHelper$.MODULE$.force(EsRequestContext.from(requestInfo));
    } catch (Exception ex) {
      throw new SecurityPermissionException("Cannot create request context object", ex);
    }
  }

  private Consumer<RorEngineFactory.__old_Engine> scheduleDelayedEngineShutdown(Duration delay) {
    return engine -> scheduler.schedule(engine::shutdown, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static boolean shouldSkipACL(boolean chanNull, boolean reqNull) {

    // This was not a REST message
    if (reqNull && chanNull) {
      return true;
    }

    // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
    if (reqNull != chanNull) {
      if (chanNull) {
        throw new SecurityPermissionException(
            "Problems analyzing the channel object. " + "Have you checked the security permissions?", null);
      }
      if (reqNull) {
        throw new SecurityPermissionException(
            "Problems analyzing the request object. " + "Have you checked the security permissions?", null);
      }
    }
    return false;
  }
}
