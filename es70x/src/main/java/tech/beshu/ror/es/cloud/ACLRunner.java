package tech.beshu.ror.es.cloud;

import com.google.common.collect.Sets;
import com.sun.deploy.ref.AppModel;
import monix.execution.Scheduler$;
import monix.execution.schedulers.CanBlock;
import monix.execution.schedulers.CanBlock$;
import org.elasticsearch.client.Client;
import scala.Option;
import scala.collection.immutable.Set;
import scala.concurrent.duration.Duration;
import squants.information.Information;
import tech.beshu.ror.SecurityPermissionException;
import tech.beshu.ror.acl.blocks.Block;
import tech.beshu.ror.acl.blocks.VariablesResolver;
import tech.beshu.ror.acl.domain;
import tech.beshu.ror.acl.helpers.RorEngineFactory;
import tech.beshu.ror.acl.helpers.RorEngineFactory$;
import tech.beshu.ror.acl.logging.AuditSink;
import tech.beshu.ror.acl.request.EsRequestContext;
import tech.beshu.ror.acl.request.RequestContext;
import tech.beshu.ror.acl.utils.ScalaJavaHelper$;
import tech.beshu.ror.es.AuditSinkImpl;
import tech.beshu.ror.es.ESContextImpl;
import tech.beshu.ror.es.RequestInfo;
import tech.beshu.ror.es.cloud.model.ESAwareRequest;
import tech.beshu.ror.settings.BasicSettings;
import tech.beshu.ror.shims.es.LoggerShim;
import tech.beshu.ror.utils.RCUtils;

import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Instant;
import java.util.Collections;

public class ACLRunner {

  private final LoggerShim logger;
  private final ESContextImpl esContext;
  private final RorEngineFactory.Engine engine;

  ACLRunner() {
    this.logger = new LoggerShim() {
      @Override
      public void trace(String message) {

      }

      @Override
      public void info(String message) {
        System.out.println(message);
      }

      @Override
      public void debug(String message) {
        System.out.println(message);

      }

      @Override
      public void warn(String message) {
        System.out.println(message);

      }

      @Override
      public void warn(String message, Throwable t) {
        System.out.println(message);

      }

      @Override
      public void error(String message, Throwable t) {
        System.out.println(message);

      }

      @Override
      public void error(String message) {
        System.out.println(message);

      }

      @Override
      public boolean isDebugEnabled() {
        return true;
      }
    };

    this.esContext = new ESContextImpl(BasicSettings.fromFile(logger, Paths.get("/tmp/readonlyrest.yml"), Collections.emptyMap()));

    this.engine = AccessController.doPrivileged((PrivilegedAction<RorEngineFactory.Engine>) () ->
        RorEngineFactory$.MODULE$
            .reload(
                new AuditSink() {
                  @Override
                  public void submit(String indexName, String documentId, String jsonRecord) {
                    System.out.println(jsonRecord);
                  }
                },
                esContext.getSettings().getRaw().yaml()
            )
            .runSyncUnsafe(scala.concurrent.duration.Duration.Inf(), Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit())
    );
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

  public Block.ExecutionResult run(ESAwareRequest ear) {
    //     engine.acl().handle(new RequestContext() {
    //      @Override
    //      public VariablesResolver variablesResolver() {
    //        return null;//super.variablesResolver();
    //      }
    //
    //      @Override
    //      public domain.Type type() {
    //        return domain.Type.apply(ear.getActionRequest().getClass().getSimpleName());
    //      }
    //
    //      @Override
    //      public Set<domain.IndexWithAliases> allIndicesAndAliases() {
    //        return null;//Sets.newHashSet(domain.IndexWithAliases.apply(domain.IndexName.apply("index1"), scala.collection.Set.empty()));
    //      }
    //
    //      @Override
    //      public String content() {
    //        return null;
    //      }
    //
    //      @Override
    //      public Set<domain.IndexName> repositories() {
    //        return null;
    //      }
    //
    //      @Override
    //      public domain.Action action() {
    //        return null;
    //      }
    //
    //      @Override
    //      public Id id() {
    //        return null;
    //      }
    //
    //      @Override
    //      public domain.UriPath uriPath() {
    //        return null;
    //      }
    //
    //      @Override
    //      public Option<domain.Address> remoteAddress() {
    //        return null;
    //      }
    //
    //      @Override
    //      public Instant timestamp() {
    //        return null;
    //      }
    //
    //      @Override
    //      public Set<domain.Header> headers() {
    //        return null;
    //      }
    //
    //      @Override
    //      public boolean isAllowedForDLS() {
    //        return false;
    //      }
    //
    //      @Override
    //      public String method() {
    //        return null;
    //      }
    //
    //      @Override
    //      public boolean isCompositeRequest() {
    //        return false;
    //      }
    //
    //      @Override
    //      public Set<domain.IndexName> snapshots() {
    //        return null;
    //      }
    //
    //      @Override
    //      public boolean involvesIndices() {
    //        return false;
    //      }
    //
    //      @Override
    //      public boolean hasRemoteClusters() {
    //        return false;
    //      }
    //
    //      @Override
    //      public Set<domain.IndexName> indices() {
    //        return null;
    //      }
    //
    //      @Override
    //      public domain.Address localAddress() {
    //        return null;
    //      }
    //
    //      @Override
    //      public boolean isReadOnlyRequest() {
    //        return false;
    //      }
    //
    //      @Override
    //      public Information contentLength() {
    //        return null;
    //      }
    //
    //      @Override
    //      public long taskId() {
    //        return 0;
    //      }
    //    }).runSyncUnsafe(Duration.Inf(),null, CanBlock.permit()).handlingResult();
    //    return null;
    //  };
    return null;
  }
}
