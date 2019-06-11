package tech.beshu.ror.es.cloud;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerOptions;
import tech.beshu.ror.es.cloud.model.ESAwareRequest;
import tech.beshu.ror.es.cloud.model.IncomingRequest;

import java.util.List;
import java.util.Map;

public class ProxyVerticle extends AbstractVerticle {
  private final Vertx vertx;
  private final ESSimulator esSimulator;
  private HostAndPort esEndpoint;

  private int localport;

  ProxyVerticle(int localport, HostAndPort esEndpoint, ESSimulator es) {
    this.localport = localport;
    this.esEndpoint = esEndpoint;
    this.esSimulator = es;

    vertx = Vertx.vertx();
    vertx.deployVerticle(ProxyVerticle.class, new DeploymentOptions());
  }

  @Override
  public void start() {

    System.out.println("Creating proxy server: local port " + localport + ", remote: " + esEndpoint);
    HttpClient client = vertx.createHttpClient(
        new HttpClientOptions()
            //            .setSsl(true)
            //            .setTrustAll(true)
            .setLogActivity(true)
    );
    vertx.createHttpServer(new HttpServerOptions().setLogActivity(true)).requestHandler(req -> {
      Map<String, List<String>> hrs = Maps.newHashMap();
      for (Map.Entry<String, String> e : req.headers().entries()) {
        List<String> values = hrs.getOrDefault(e.getKey(), Lists.newArrayList());
        values.add(e.getValue());
        hrs.put(e.getKey(), values);
      }

      // #TODO this thing only proxies requests without body...
      //      req.bodyHandler(bodyBuffer ->{
      //      });

      System.out.println("Proxying request: " + req.uri());

      ESAwareRequest result = esSimulator.parseRequest(new IncomingRequest(req.method().name(), req.uri(), "", hrs));
      if (result == null) {
        req.response().write("rejected");
        req.response().end();
        return;
      }
      System.out.println("REQUEST PARSED SUCCESSFULLY " + result.getAction());

      HttpClientRequest c_req = client.request(req.method(), esEndpoint.getPort(), esEndpoint.getHost(), req.uri(), c_res -> {
        System.out.println("Proxying response: " + c_res.statusCode());
        req.response().setChunked(true);
        req.response().setStatusCode(c_res.statusCode());
        req.response().headers().setAll(c_res.headers());
        c_res.handler(data -> {
          System.out.println("Proxying response body: " + data.toString("ISO-8859-1"));
          req.response().write(data);
        });
        c_res.endHandler((v) -> req.response().end());
      });
      c_req.setChunked(true);
      c_req.headers().setAll(req.headers());
      req.handler(data -> {
        System.out.println("Proxying request body " + data.toString("ISO-8859-1"));
        c_req.write(data);
      });
      req.endHandler((v) -> c_req.end());
    }).listen(localport);
  }

  @Override
  public Vertx getVertx() {
    return vertx;
  }

  @Override
  public void init(Vertx vertx, Context context) {
    System.out.println("initializing proxy verticle");
  }

  @Override
  public void stop() throws Exception {
    vertx.close();
  }
}
