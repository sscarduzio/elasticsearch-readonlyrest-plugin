package org.elasticsearch.rest.action.readonlyrest.acl;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.netty.channel.socket.SocketChannel;
import org.elasticsearch.http.netty.NettyHttpChannel;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;

public class ACLRequest {

  /**
   * A regular expression to match the various representations of "localhost"
   */
  private final static Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private final static String  LOCALHOST   = "127.0.0.1";

  private static ESLogger      logger;
  private String               address;
  private String               uri;
  private Integer              bodyLength;
  private Method               method;

  @Override
  public String toString() {
    return method +" "+ uri + " len: "+ bodyLength + " originator address: " + address;
  }
  public String getAddress() {
    return address;
  }

  public String getUri() {
    return uri;
  }

  public Integer getBodyLength() {
    return bodyLength;
  }

  public ACLRequest(RestRequest request, RestChannel channel) {
    this(request.uri(), getAddress(channel), request.content().length(), request.method());
    String content = request.content().toUtf8();
  }
  
  public ACLRequest(String uri, String address, Integer bodyLength, Method method){
    this.uri = uri;
    this.address = address;
    this.bodyLength = bodyLength;
    this.method = method;
  }

  static String getAddress(RestChannel channel) {
    String remoteHost = null;
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
      logger.error("error checking the host", e);
      return null;
    }
    return remoteHost;
  }

  public Method getMethod() {
    return method;
  }

}
