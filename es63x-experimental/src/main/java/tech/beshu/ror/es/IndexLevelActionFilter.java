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
import org.elasticsearch.client.Client;
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
import scala.collection.JavaConverters$;
import scala.collection.immutable.Map;
import scala.concurrent.duration.FiniteDuration;
import tech.beshu.ror.SecurityPermissionException;
import tech.beshu.ror.acl.AclHandler;
import tech.beshu.ror.acl.ResponseWriter;
import tech.beshu.ror.acl.blocks.BlockContext;
import tech.beshu.ror.acl.factory.RorEngineFactory;
import tech.beshu.ror.acl.factory.RorEngineFactory$;
import tech.beshu.ror.acl.factory.RorEngineFactory.Engine;
import tech.beshu.ror.acl.logging.AuditSink;
import tech.beshu.ror.acl.request.EsRequestContext;
import tech.beshu.ror.acl.request.RequestContext;
import tech.beshu.ror.acl.utils.ScalaJavaHelper$;
import tech.beshu.ror.settings.BasicSettings;
import tech.beshu.ror.shims.es.ESContext;

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
@Singleton public class IndexLevelActionFilter implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final AtomicReference<Optional<RorEngineFactory.Engine>> rorEngine;
  private final AtomicReference<ESContext> context = new AtomicReference<>();
  private final IndexNameExpressionResolver indexResolver;
  private final Logger logger;

  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final Boolean hasRemoteClusters;

  public IndexLevelActionFilter(Settings settings, ClusterService clusterService, NodeClient client,
      ThreadPool threadPool, SettingsObservableImpl settingsObservable, Environment env, Boolean hasRemoteClusters) {
    this.hasRemoteClusters = hasRemoteClusters;
    System.setProperty("es.set.netty.runtime.available.processors", "false");

    logger = LogManager.getLogger(this.getClass());
    BasicSettings baseSettings = BasicSettings.fromFileObj(ESContextImpl.mkLoggerShim(logger),
        env.configFile().toAbsolutePath(), settings);

    this.context.set(new ESContextImpl(client, baseSettings));

    this.clusterService = clusterService;
    this.indexResolver = new IndexNameExpressionResolver(settings);
    this.threadPool = threadPool;
    this.rorEngine = new AtomicReference<>(Optional.empty());

    settingsObservable.addObserver((o, arg) -> {
      logger.info("Settings observer refreshing...");
      Environment newEnv = new Environment(settings, env.configFile().toAbsolutePath());
      BasicSettings newBasicSettings = new BasicSettings(settingsObservable.getCurrent(),
          newEnv.configFile().toAbsolutePath());
      ESContext newContext = new ESContextImpl(client, newBasicSettings);
      this.context.set(newContext);

      if (newContext.getSettings().isEnabled()) {
        FiniteDuration timeout = scala.concurrent.duration.FiniteDuration.apply(10, TimeUnit.SECONDS);
        Engine engine = RorEngineFactory$.MODULE$.reload(createAuditSink(client, newBasicSettings),
            newContext.getSettings().getRaw().yaml()).runSyncUnsafe(timeout, Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit());
        Optional<Engine> oldEngine = rorEngine.getAndSet(Optional.of(engine));
        oldEngine.ifPresent(scheduleDelayedEngineShutdown(Duration.ofSeconds(10)));
        logger.info("Configuration reloaded - ReadonlyREST enabled");
      }
      else {
        Optional<Engine> oldEngine = rorEngine.getAndSet(Optional.empty());
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
  public <Request extends ActionRequest, Response extends ActionResponse> void apply(Task task, String action,
      Request request, ActionListener<Response> listener, ActionFilterChain<Request, Response> chain) {
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      logger.debug("THREAD_CTX: " + threadPool.getThreadContext().hashCode());
      Optional<Engine> engine = this.rorEngine.get();
      if (engine.isPresent()) {
        handleRequest(engine.get(), task, action, request, listener, chain);
      }
      else {
        chain.proceed(task, action, request, listener);
      }
      return null;
    });
  }

  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(Engine engine, Task task,
      String action, Request request, ActionListener<Response> listener, ActionFilterChain<Request, Response> chain) {
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
    RequestInfo requestInfo = new RequestInfo(channel, task.getId(), action, request, clusterService, threadPool,
        context.get(), hasRemoteClusters);
    RequestContext requestContext = requestContextFrom(requestInfo);

    engine.acl().handle(requestContext, new AclHandler() {
      @Override
      public ResponseWriter onAllow(BlockContext blockContext) {
        try {
          // Cache disabling for those 2 kind of request is crucial for
          // document level security to work. Otherwise we'd get an answer from
          // the cache some times and would not be filtered
          if (engine.context().involvesFilter()) {
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

          ResponseActionListener searchListener = new ResponseActionListener((ActionListener<ActionResponse>) listener,
              requestContext, blockContext);
          return createWriter(task, action, request, chain, requestInfo, (ActionListener<Response>) searchListener);
        } catch (Throwable e) {
          logger.error("on allow exception", e);
          return createWriter(task, action, request, chain, requestInfo, listener);
        }
      }

      @Override
      public void onForbidden() {
        ElasticsearchStatusException exc = new ElasticsearchStatusException(
            context.get().getSettings().getForbiddenMessage(),
            engine.context().doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN) {
          @Override
          public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field("reason", context.get().getSettings().getForbiddenMessage());
            return builder;
          }
        };
        if (engine.context().doesRequirePassword()) {
          exc.addHeader("WWW-Authenticate", "Basic");
        }
        listener.onFailure(exc);
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
      public void onError(Throwable t) {
        listener.onFailure((Exception) t);
      }
    }).runAsyncAndForget(Scheduler$.MODULE$.global());
  }

  private <Request extends ActionRequest, Response extends ActionResponse> ResponseWriter createWriter(Task task,
      String action, Request request, ActionFilterChain<Request, Response> chain, RequestInfo requestInfo,
      ActionListener<Response> searchListener) {
    return new ResponseWriter() {
      @Override
      public void writeResponseHeaders(Map<String, String> headers) {
        requestInfo.writeResponseHeaders(JavaConverters$.MODULE$.mapAsJavaMap(headers));
      }

      @Override
      public void writeToThreadContextHeader(String key, String value) {
        requestInfo.writeToThreadContextHeader(key, value);
      }

      @Override
      public void writeIndices(scala.collection.immutable.Set<String> indices) {
        requestInfo.writeIndices(JavaConverters$.MODULE$.setAsJavaSet(indices));
      }

      @Override
      public void writeSnapshots(scala.collection.immutable.Set<String> indices) {
        requestInfo.writeSnapshots(JavaConverters$.MODULE$.setAsJavaSet(indices));
      }

      @Override
      public void writeRepositories(scala.collection.immutable.Set<String> indices) {
        requestInfo.writeRepositories(JavaConverters$.MODULE$.setAsJavaSet(indices));
      }

      @Override
      public void commit() {
        chain.proceed(task, action, request, searchListener);
      }
    };
  }

  private AuditSink createAuditSink(Client client, BasicSettings settings) {
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

  private Consumer<Engine> scheduleDelayedEngineShutdown(Duration delay) {
    return new Consumer<Engine>() {
      @Override
      public void accept(Engine engine) {
        scheduler.schedule(new Runnable() {
          @Override
          public void run() {
            engine.shutdown();
          }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
      }
    };
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
