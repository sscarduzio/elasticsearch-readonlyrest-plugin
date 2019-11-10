package tech.beshu.ror.es.cloud;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.http.HttpRequest;
import org.elasticsearch.http.HttpResponse;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterService;
import org.elasticsearch.usage.UsageService;
import scala.Option;
import tech.beshu.ror.es.IndexLevelActionFilter2;
import tech.beshu.ror.es.cloud.model.ESAwareRequest;
import tech.beshu.ror.es.cloud.model.IncomingRequest;
import tech.beshu.ror.es.utils.ThreadRepo2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;

public class ESSimulator {
  ActionModule actionModule;
  private IncomingRequest currentIncomingRequest;
  private CompletableFuture<Void> currentFuture;
  private ESAwareRequest esAwareRequest;

  ThreadPool tp = new ThreadPool(Settings.EMPTY,
      new FixedExecutorBuilder(Settings.EMPTY, "a", 1, 1, "a-"));

  public ESSimulator() throws IOException {

    String settingsJson = "{\"action.destructive_requires_name\": false,\"client.type\":\"node\",\"cluster.initial_master_nodes\":\"n1_it\",\"cluster.name\":\"elasticsearch\",\"http.type\":\"ssl_netty4\",\"http.type.default\":\"netty4\",\"node.name\":\"n1_it\",\"path.home\":\"integration-tests/src/test/eshome/\",\"path.logs\":\"/tmp/logs\",\"path.repo\":\"/tmp/lolrepo1\",\"transport.type.default\":\"netty4\"}\n";
    XContentParser xc = XContentFactory
        .xContent(XContentType.JSON)
        .createParser(null, null, settingsJson);

    Settings csettings = Settings.fromXContent(xc);
    NodeClient nc = new NodeClient(csettings, tp);

    CircuitBreakerService cbs = new NoneCircuitBreakerService();
    UsageService us = new UsageService();
    ClusterSettings cs = new ClusterSettings(csettings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS, Sets.newHashSet());
    ClusterService clusterService = new ClusterService(
        csettings,
        cs,
        tp
    );

    IndexNameExpressionResolver iner = null;
    SettingsFilter sf = null;
    actionModule = new ActionModule(
        false,
        csettings,
        iner,
        IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
        cs,
        sf, //new SettingsFilter(Sets.newHashSet("*")),
        tp,
        Collections.emptyList(),
        nc,
        cbs,
        us
    );
   // System.out.println(actionModule.getActions());
    clusterService
        .getClusterApplierService()
        .setInitialState(
            ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.get(csettings))
                .blocks(ClusterBlocks.builder().addGlobalBlock(STATE_NOT_RECOVERED_BLOCK))
                .build()
        );

    IndexLevelActionFilter2 iaf = new IndexLevelActionFilter2(
        clusterService,
        nc,
        tp,
        new Environment(csettings, null),
        Option::empty
    );

    ActionFilters afs = new ActionFilters(
        Sets.newHashSet(
            iaf,
            new ActionFilter.Simple() {
              @Override
              protected boolean apply(String action, ActionRequest request, ActionListener<?> listener) {
                System.out.println("applying AF");
                esAwareRequest = new ESAwareRequest(currentIncomingRequest, action, request);
                currentFuture.complete(null);
                return true;
              }

              @Override
              public int order() {
                return 1;
              }
            }));
    TaskManager tm = new TaskManager(csettings, tp, Collections.EMPTY_SET);

    Map<Action, TransportAction> actionMap = Maps.newHashMap();
    actionModule.getActions().keySet().forEach(a -> {
      actionMap.put(new Action(a) {
        @Override
        public ActionResponse newResponse() {
          return null;
        }
      }, new TransportAction(actionModule.getActions().get(a).getAction().name(), afs, tm) {
        @Override
        protected void doExecute(Task task, ActionRequest request, ActionListener listener) {

        }
      });
    });

    RemoteClusterService rcs = null; // new RemoteClusterService(csettings, ts);

    nc.initialize(actionMap, () -> "n1", rcs);

    actionModule.initRestHandlers(() -> DiscoveryNodes.EMPTY_NODES);

  }

  private static void logReq(ESAwareRequest ear){
    System.out.println("HTTP level request parsed into ES level request: " +
        ear.getIncomingRequest().getMethod() + ", " +
        ear.getIncomingRequest().getUri() + " -> " +
        ear.getAction() + ", " +
        ear.getActionRequest().getClass().getSimpleName()
    );
  }
  public static void main(String[] args) throws IOException {
    ESSimulator es = new ESSimulator();

   // logReq(es.processRequest(new IncomingRequest("GET", "/_cluster/health", "", Collections.emptyMap())));
   // logReq(es.processRequest(new IncomingRequest("GET", "/idx/doc/1", "", Collections.emptyMap())));
    //logReq(es.processRequest(new IncomingRequest("POST", "/_msearch", "{}", Collections.emptyMap())));


  }

  public RestResponse processRequest(IncomingRequest ireq) {
    HttpRequest hr = new HttpRequest() {
      @Override
      public RestRequest.Method method() {
        return RestRequest.Method.valueOf(ireq.getMethod());
      }

      @Override
      public String uri() {
        return ireq.getUri();
      }

      @Override
      public BytesReference content() {
        // #TODO Optimize: Should not read to string and convert the payload all the times
        return BytesReference.fromByteBuffers(new ByteBuffer[] { ByteBuffer.wrap(ireq.getBody().getBytes()) });
      }

      @Override
      public Map<String, List<String>> getHeaders() {
        return ireq.getHeaders();
      }

      @Override
      public List<String> strictCookies() {
        return Collections.EMPTY_LIST;
      }

      @Override
      public HttpVersion protocolVersion() {
        return HttpVersion.HTTP_1_1;
      }

      @Override
      public HttpRequest removeHeader(String header) {
        return this;
      }

      @Override
      public HttpResponse createResponse(RestStatus status, BytesReference content) {
        return new HttpResponse() {
          @Override
          public void addHeader(String name, String value) {

          }

          @Override
          public boolean containsHeader(String name) {
            return false;
          }
        };
      }
    };
    HttpChannel httpChannel = new HttpChannel() {
      @Override
      public void sendResponse(HttpResponse response, ActionListener<Void> listener) {

      }

      @Override
      public InetSocketAddress getLocalAddress() {
        return new InetSocketAddress(4000);
      }

      @Override
      public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(4000);
      }

      @Override
      public void close() {

      }

      @Override
      public void addCloseListener(ActionListener<Void> listener) {

      }

      @Override
      public boolean isOpen() {
        return false;
      }
    };
    RestRequest restreq = RestRequest.request(
        NamedXContentRegistry.EMPTY,
        hr,
        httpChannel
    );

    AtomicReference<RestResponse> restResponse = new AtomicReference<>();
    RestChannel rchan = new RestChannel() {

      @Override
      public XContentBuilder newBuilder() throws IOException {
        return XContentBuilder.builder(XContentFactory.xContent(XContentType.JSON));
      }

      @Override
      public XContentBuilder newErrorBuilder() throws IOException {
        return XContentBuilder.builder(XContentFactory.xContent(XContentType.JSON));
      }

      @Override
      public XContentBuilder newBuilder(XContentType xContentType, boolean useFiltering) throws IOException {
        return XContentBuilder.builder(XContentFactory.xContent(xContentType));
      }

      @Override
      public XContentBuilder newBuilder(XContentType xContentType, XContentType responseContentType, boolean useFiltering) throws IOException {
        return XContentBuilder.builder(XContentFactory.xContent(XContentType.JSON));
      }

      @Override
      public BytesStreamOutput bytesOutput() {
        return new BytesStreamOutput();
      }

      @Override
      public RestRequest request() {
        return restreq;
      }

      @Override
      public boolean detailedErrorsEnabled() {
        return false;
      }

      @Override
      public void sendResponse(RestResponse response) {
        System.out.println("send response " + response.status() + ": " +response.content().utf8ToString());
        restResponse.set(response);
      }
    };
    ThreadRepo2.setRestChannel(rchan);

    this.currentIncomingRequest = ireq;
    currentFuture = CompletableFuture.supplyAsync(() -> {
      this.actionModule.getRestController().dispatchRequest(
          restreq,
          rchan,
          tp.getThreadContext()
      );
      return null;
    });
    currentFuture.join();
    return restResponse.get();
  }

}
