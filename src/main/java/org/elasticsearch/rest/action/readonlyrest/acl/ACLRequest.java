package org.elasticsearch.rest.action.readonlyrest.acl;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import java.util.Map;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.http.netty.NettyHttpChannel;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.ConfigurationHelper;
import org.jboss.netty.channel.socket.SocketChannel;

public class ACLRequest {

  /**
   * A regular expression to match the various representations of "localhost"
   */
  private final static Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private final static String  LOCALHOST   = "127.0.0.1";

  private static ESLogger      logger;
  private String               address;
  private String               apiKey;
  private String               authKey;
  private String               uri;
  private Integer              bodyLength;
  private Method               method;

  @Override
  public String toString() {
    return method +" "+ uri + " len: "+ bodyLength + " originator address: " + address + " api key: " + apiKey;
  }
  public String getAddress() {
    return address;
  }

  public String getApiKey() {return apiKey; }

  public String getAuthKey() {return authKey; }

  public String getUri() {
    return uri;
  }

  public Integer getBodyLength() {
    return bodyLength;
  }

  public ACLRequest(RestRequest request, RestChannel channel) {
    this(request.uri(), getAddress(request, channel), request.header("X-Api-Key"), request.header("Authorization"), request.content().length(), request.method());

    ESLogger logger = ESLoggerFactory.getLogger(ACLRequest.class.getName());
    logger.debug("Headers:\n");
    for (Map.Entry<String, String> header : request.headers()) {
        logger.debug(header.getKey() + "=" + header.getValue());
    }
  }
  
  public ACLRequest(String uri, String address, String apiKey, String authKey, Integer bodyLength, Method method){
    this.uri = uri;
    this.address = address;
    this.apiKey = apiKey;
    this.authKey = authKey;
    this.bodyLength = bodyLength;
    this.method = method;
  }

  static String getAddress(RestRequest request, RestChannel channel) {
    String remoteHost = null;

    if (!ConfigurationHelper.isNullOrEmpty(request.header("X-Forwarded-For"))) {
      String[] parts = request.header("X-Forwarded-For").split(",");
      if (!ConfigurationHelper.isNullOrEmpty(parts[0])) {
        return parts[0];
      }
    }

    try {
      NettyHttpChannel obj = (NettyHttpChannel) channel;
      Field f;
      f = obj.getClass().getDeclaredField("channel");
      f.setAccessible(true);
      SocketChannel sc = (SocketChannel) f.get(obj);
      InetSocketAddress remoteHostAddr = sc.getRemoteAddress();
      remoteHost = remoteHostAddr.getAddress().getHostAddress();
      // Make sure we recognize localhost even when IPV6 is involved
      if (localhostRe.matcher(remoteHost).find()) {
        remoteHost = LOCALHOST;
      }
    }
    catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }
    return remoteHost;
  }

  public Method getMethod() {
    return method;
  }

}
