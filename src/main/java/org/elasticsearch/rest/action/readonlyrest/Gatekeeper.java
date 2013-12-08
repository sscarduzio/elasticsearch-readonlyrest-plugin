package org.elasticsearch.rest.action.readonlyrest;

import static org.elasticsearch.rest.RestRequest.Method.GET;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.regex.Pattern;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.netty.channel.socket.SocketChannel;
import org.elasticsearch.http.netty.NettyHttpChannel;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest.Method;

/**
 * Gatekeeper
 * 
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 */

public class Gatekeeper {

  /**
   * A regular expression to match the various representations of "localhost"
   */
  private final static Pattern localhost = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1|localhost)$");

  private ESLogger             logger;
  private ConfigurationHelper  conf;

  public Gatekeeper(ESLogger logger, ConfigurationHelper conf) {
    this.logger = logger;
    this.conf = conf;
  }

  boolean matchesRegexp(Pattern forbiddenUriRe, String uri) {
    // Bar by URI
    if(forbiddenUriRe != null && forbiddenUriRe.matcher(uri).find()){
     logger.info("matches regexp");
      return true; 
    }
    return false;
  }

  Boolean isHostInternal(RestChannel channel) {
    String remoteHost = null;
    try {
      NettyHttpChannel obj = (NettyHttpChannel) channel;
      Field f;
      f = obj.getClass().getDeclaredField("channel");
      f.setAccessible(true);
      SocketChannel sc = (SocketChannel) f.get(obj);
      remoteHost = sc.getRemoteAddress().getHostName();
    }
    catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
      e.printStackTrace();
      logger.error("error checking the host", e);
      return false;
    }
    return doIsHostInternal(remoteHost);
  }

  private Boolean doIsHostInternal(String remoteHost) {
    if (conf.isAllowLocalhost()) {
      Boolean isLocal = localhost.matcher(remoteHost).find();
      if (isLocal) {
        logger.info("internal");
        return true;
      }
    }
    Set<String> whitelist = conf.getWhitelist();
    if (whitelist != null && whitelist.size() > 0 && whitelist.contains(remoteHost)) {
      logger.info("internal");
      return true;
    }
    logger.info("external");
    return false;
  }

  /**
   * Will reject non-RFC2616 compliant HTTP GET calls.
   * 
   * @see <a href="http://stackoverflow.com/a/15656884/1094616">Stack Overflow analysis</a>
   * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.3">HTTP specs (RFC2616)</a>
   */
  Boolean isRequestReadonly(Method m, int contentSize) {
    // Reject by RFC2616
    if( m.equals(GET) && contentSize == 0){
      logger.info("is readonly");
      return true;
    }
    return false;
  }

}
