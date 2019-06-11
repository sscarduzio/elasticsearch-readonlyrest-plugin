package tech.beshu.ror.es.cloud;

import com.google.common.net.HostAndPort;
import io.vertx.core.AbstractVerticle;
import tech.beshu.ror.es.cloud.model.IncomingRequest;

import java.io.IOException;
import java.util.Collections;

public class Cloud extends AbstractVerticle {

  private ESSimulator es;

  private ProxyVerticle pv;

  public Cloud() throws IOException {
    long start = System.currentTimeMillis();
    es = new ESSimulator();
    System.out.println("Millisecs ESSimulator init: " + (System.currentTimeMillis() - start));

    start = System.currentTimeMillis();
    pv = new ProxyVerticle(5000, HostAndPort.fromString("localhost:9200"), es);
    pv.start();
    System.out.println("Millisecs ProxyVerticle init: " + (System.currentTimeMillis() - start));

    IncomingRequest ir = new IncomingRequest("GET", "/index/a/1", "", Collections.emptyMap());

//    for (int i = 0; i<1000; i++ ) {
//      final long finalStart = System.currentTimeMillis();
//      es.parseRequest(ir, (_ir, action, aReq) -> {
//        System.out.println(System.currentTimeMillis() - finalStart + " "+ _ir + " >> " + action + " >> " + aReq);
//        return null;
//      });
//    }
  }

  // Convenience method so you can run it in your IDE
  public static void main(String[] args) throws Exception {
    Cloud c = new Cloud();
    ACLRunner ar = new ACLRunner();
    Thread.sleep(Long.MAX_VALUE);
  }

}