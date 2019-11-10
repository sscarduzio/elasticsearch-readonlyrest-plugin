//package tech.beshu.ror.es.cloud;
//
//import com.google.common.collect.Sets;
//import com.sun.deploy.ref.AppModel;
//import monix.execution.Scheduler$;
//import monix.execution.schedulers.CanBlock;
//import monix.execution.schedulers.CanBlock$;
//import org.elasticsearch.client.Client;
//import scala.Option;
//import scala.collection.immutable.Set;
//import scala.concurrent.duration.Duration;
//import squants.information.Information;
//import tech.beshu.ror.SecurityPermissionException;
//import tech.beshu.ror.acl.blocks.Block;
//import tech.beshu.ror.acl.blocks.VariablesResolver;
//import tech.beshu.ror.acl.domain;
//import tech.beshu.ror.acl.helpers.RorEngineFactory;
//import tech.beshu.ror.acl.helpers.RorEngineFactory$;
//import tech.beshu.ror.acl.logging.AuditSink;
//import tech.beshu.ror.acl.request.EsRequestContext;
//import tech.beshu.ror.acl.request.RequestContext;
//import tech.beshu.ror.acl.utils.ScalaJavaHelper$;
//import tech.beshu.ror.boot.Engine;
//import tech.beshu.ror.es.AuditSinkImpl;
//import tech.beshu.ror.es.ESContextImpl;
//import tech.beshu.ror.es.RequestInfo;
//import tech.beshu.ror.es.cloud.model.ESAwareRequest;
//import tech.beshu.ror.settings.BasicSettings;
//import tech.beshu.ror.shims.es.LoggerShim;
//import tech.beshu.ror.utils.RCUtils;
//
//import java.nio.file.Paths;
//import java.security.AccessController;
//import java.security.PrivilegedAction;
//import java.time.Instant;
//import java.util.Collections;
//
//public class ACLRunner {
//
//  private final ESContextImpl esContext;
//  private final RorEngineFactory.Engine engine;
//
//  ACLRunner() {
//
//    this.esContext = new ESContextImpl(BasicSettings.fromFile(logger, Paths.get("/tmp/readonlyrest.yml"), Collections.emptyMap()));
//
//    this.engine = AccessController.doPrivileged((PrivilegedAction<Engine>) () ->
//
//        RorEngineFactory$.MODULE$
//            .reload(
//                new AuditSink() {
//                  @Override
//                  public void submit(String indexName, String documentId, String jsonRecord) {
//                    System.out.println(jsonRecord);
//                  }
//                },
//                esContext.getSettings().getRaw().yaml()
//            )
//            .runSyncUnsafe(scala.concurrent.duration.Duration.Inf(), Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit())
//    );
//  }
//
//  private AuditSink createAuditSink(Client client, BasicSettings settings) {
//    return new AuditSink() {
//      AuditSinkImpl auditSink = new AuditSinkImpl(client, settings);
//
//      @Override
//      public void submit(String indexName, String documentId, String jsonRecord) {
//        auditSink.submit(indexName, documentId, jsonRecord);
//      }
//    };
//  }
//
//  private RequestContext requestContextFrom(RequestInfo requestInfo) {
//    try {
//      return ScalaJavaHelper$.MODULE$.force(EsRequestContext.from(requestInfo));
//    } catch (Exception ex) {
//      throw new SecurityPermissionException("Cannot create request context object", ex);
//    }
//  }
//
//  public Block.ExecutionResult run(ESAwareRequest ear) {
//    return null;
//  }
//}
