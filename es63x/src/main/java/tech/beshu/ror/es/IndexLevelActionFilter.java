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
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterService;
import scala.Function1;
import scala.Option;
import scala.collection.JavaConverters$;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.util.Either;
import tech.beshu.ror.SecurityPermissionException;
import tech.beshu.ror.acl.AclActionHandler;
import tech.beshu.ror.acl.AclHandlingResult;
import tech.beshu.ror.acl.AclResultCommitter;
import tech.beshu.ror.acl.AclStaticContext;
import tech.beshu.ror.acl.BlockContextJavaHelper$;
import tech.beshu.ror.acl.blocks.BlockContext;
import tech.beshu.ror.acl.request.EsRequestContext;
import tech.beshu.ror.acl.request.RequestContext;
import tech.beshu.ror.boot.Engine;
import tech.beshu.ror.boot.Ror$;
import tech.beshu.ror.boot.RorInstance;
import tech.beshu.ror.boot.StartingFailure;
import tech.beshu.ror.es.scala.RequestInfo;
import tech.beshu.ror.es.scala.ResponseActionListener;
import tech.beshu.ror.utils.ScalaJavaHelper$;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static tech.beshu.ror.es.utils.ThreadLocalRestChannelProviderHelper.getRestChannel;

/**
 * Created by sscarduzio on 19/12/2015.
 */
@Singleton
public class IndexLevelActionFilter implements ActionFilter {

  private final ThreadPool threadPool;
  private final ClusterService clusterService;

  private final RorInstance rorInstance;

  private final Supplier<Optional<RemoteClusterService>> remoteClusterServiceSupplier;
  private final Logger logger = LogManager.getLogger(this.getClass());

  public IndexLevelActionFilter(
      ClusterService clusterService,
      NodeClient client,
      ThreadPool threadPool,
      Environment env,
      Supplier<Optional<RemoteClusterService>> remoteClusterServiceSupplier
  ) {
    this.remoteClusterServiceSupplier = remoteClusterServiceSupplier;
    this.clusterService = clusterService;
    this.threadPool = threadPool;

    FiniteDuration startingTimeout = scala.concurrent.duration.FiniteDuration.apply(1, TimeUnit.MINUTES);

    Either<StartingFailure, RorInstance> result = AccessController.doPrivileged((PrivilegedAction<Either<StartingFailure, RorInstance>>) () ->
        Ror$.MODULE$.start(
            env.configFile(),
            createAuditSink(client),
            new EsIndexJsonContentProvider(client)
        ).runSyncUnsafe(startingTimeout, Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit())
    );

    if(result.isRight()) {
      this.rorInstance = result.right().get();
      RorInstanceSupplier.getInstance().update(rorInstance);
    } else {
      throw StartingFailureException.from(result.left().get());
    }
  }

  public void stop() {
    rorInstance.stop();
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
      Option<Engine> engine = rorInstance.engine();
      Optional<RestChannel> restChannel = getRestChannel();
      if(!restChannel.isPresent() || action.startsWith("internal:")) {
        chain.proceed(task, action, request, listener);
      } else if (!engine.isDefined()) {
        RestChannel channel = restChannel.get();
        channel.sendResponse(RorNotReadyResponse.create(channel));
      } else {
        handleRequest(engine.get(), task, action, request, listener, chain, restChannel.get());
      }
      return null;
    });
  }


  private <Request extends ActionRequest, Response extends ActionResponse> void handleRequest(
      Engine engine,
      Task task,
      String action,
      Request request,
      ActionListener<Response> listener,
      ActionFilterChain<Request, Response> chain,
      RestChannel channel
  ) {
    Optional<RemoteClusterService> remoteClusterService = remoteClusterServiceSupplier.get();
    if(remoteClusterService.isPresent()) {
      RequestInfo requestInfo = new RequestInfo(channel, task.getId(), action, request, clusterService, threadPool,
          remoteClusterService.get());
      RequestContext requestContext = requestContextFrom(requestInfo);

      Consumer<ActionListener<Response>> proceed =
          responseActionListener -> chain.proceed(task, action, request, responseActionListener);

      engine.acl()
          .handle(requestContext)
          .runAsync(handleAclResult(engine, listener, request, requestContext, requestInfo, proceed, channel), Scheduler$.MODULE$.global());
    } else {
      listener.onFailure(new Exception("Cluster service not ready yet. Cannot continue"));
    }
  }

  private <Request extends ActionRequest, Response extends ActionResponse> Function1<Either<Throwable, AclHandlingResult>, BoxedUnit> handleAclResult(
      Engine engine,
      ActionListener<Response> listener,
      Request request,
      RequestContext requestContext,
      RequestInfo requestInfo,
      Consumer<ActionListener<Response>> chainProceed,
      RestChannel channel
  ) {
    return result -> {
      try (ThreadContext.StoredContext ctx = threadPool.getThreadContext().stashContext()) {
        if (result.isRight()) {
          AclActionHandler handler = createAclActionHandler(engine.context(), requestInfo, request, requestContext,
              listener, chainProceed, channel);
          AclResultCommitter.commit(result.right().get(), handler);
        }
        else {
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
      Consumer<ActionListener<Response>> chainProceed,
      RestChannel channel
  ) {
    return new AclActionHandler() {
      @Override
      public void onAllow(BlockContext blockContext) {
        ActionListener<Response> searchListener = createSearchListener(baseListener, request, requestContext, blockContext);
        requestInfo.writeResponseHeaders(JavaConverters$.MODULE$.mapAsJavaMap(BlockContextJavaHelper$.MODULE$.responseHeadersFrom(blockContext)));
        requestInfo.writeToThreadContextHeaders(JavaConverters$.MODULE$.mapAsJavaMap(BlockContextJavaHelper$.MODULE$.contextHeadersFrom(blockContext)));
        requestInfo.writeIndices(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.indicesFrom(blockContext)));
        requestInfo.writeSnapshots(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.snapshotsFrom(blockContext)));
        requestInfo.writeRepositories(JavaConverters$.MODULE$.setAsJavaSet(BlockContextJavaHelper$.MODULE$.repositoriesFrom(blockContext)));

        chainProceed.accept(searchListener);
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
      public void onForbidden(List<ForbiddenCause> causes) {
        channel.sendResponse(ForbiddenResponse.create(channel, causes, aclStaticContext));
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

  private AuditSink createAuditSink(Client client) {
    return new AuditSink() {
      EsAuditSink auditSink = new EsAuditSink(client);

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
