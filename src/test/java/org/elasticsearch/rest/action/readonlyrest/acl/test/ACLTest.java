package org.elasticsearch.rest.action.readonlyrest.acl.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.common.base.Charsets;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.netty.NettyHttpChannel;
import org.elasticsearch.http.netty.NettyHttpRequest;
import org.elasticsearch.http.netty.NettyHttpServerTransport;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.acl.ACL;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.Block;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.BlockExitResult;
import org.jboss.netty.channel.socket.SocketChannel;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.mockito.Mockito.*;

public class ACLTest {
  private static ACL acl;

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    try {
      byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/src/test/six_rules.yml"));
      String str = Charsets.UTF_8.decode(ByteBuffer.wrap(encoded)).toString();
      Settings s = Settings.builder().loadFromSource(str).build();
      acl = new ACL(s);
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  class ReqAndChan {
    private RestRequest r;
    private RestChannel c;

    ReqAndChan(RestRequest r, RestChannel c) {
      this.r = r;
      this.c = c;
    }
  }

  private ReqAndChan mockReq(String uri, String address, String apiKey, String authKey, Integer bodyLength, Method method, String xForwardedForHeader) throws Throwable {
    RestRequest r = mock(RestRequest.class, RETURNS_DEEP_STUBS);
    when(r.method()).thenReturn(method);
    when(r.uri()).thenReturn(uri);
    when(r.header("X-Forwarded-For")).thenReturn(xForwardedForHeader);
    when(r.header("X-Api-Key")).thenReturn(apiKey);
    when(r.header("Authorization")).thenReturn(authKey);
    when(r.content().length()).thenReturn(bodyLength);

    NettyHttpServerTransport nettyHttpServerTransport = mock(NettyHttpServerTransport.class);
    NettyHttpRequest nettyHttpRequest = mock(NettyHttpRequest.class);
    InetSocketAddress inetSocketAddress = new InetSocketAddress(address, 80);
    SocketChannel channel = mock(SocketChannel.class);
    when(nettyHttpRequest.getChannel()).thenReturn(channel);
    when(channel.getRemoteAddress()).thenReturn(inetSocketAddress);
    NettyHttpChannel c = new NettyHttpChannel(nettyHttpServerTransport, nettyHttpRequest, null, true);
    return new ReqAndChan(r, c);
  }

  // Internal/External hosts
  @Test
  public final void testAcceptExternalGet() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 0, Method.GET, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "6");
  }

  @Test
  public final void testAllowExternalOption() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 0, Method.OPTIONS, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "6");
  }

  @Test
  public final void testNetMask() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "192.168.1.5", "", "", 0, Method.POST, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "4");
  }

  // Methods + hosts
  @Test
  public final void testRejectExternalPost() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 0, Method.POST, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertFalse(res.isMatch());
  }

  @Test
  public final void testAcceptInternalGet() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "127.0.0.1", "", "", 0, Method.GET, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "4");
  }

  @Test
  public final void testAcceptInternalHead() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "127.0.0.1", "", "", 0, Method.HEAD, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertEquals(res.getBlock().getName(), "4");
  }

  // Body length
  @Test
  public final void testRejectExternalGetWithBody() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 20, Method.GET, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertFalse(res.isMatch());
  }


  // URI REGEX
  @Test
  public final void testRejectExternalURIRegEx() throws Throwable {
    ReqAndChan rc = mockReq("http://localhost:9200/reservedIdx/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 0, Method.GET, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.FORBID);
    assertEquals(res.getBlock().getName(), "5");
  }

  // API Keys
  @Test
  public final void testApiKey() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "1234567890", "", 0, Method.POST, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "3");
  }

  // HTTP Basic Auth
  @Test
  public final void testHttpBasicAuth() throws Throwable {
    String secret64 = Base64.encodeBytes("1234567890".getBytes(Charsets.UTF_8));
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "Basic " + secret64, 0, Method.POST, null);
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "2");
  }

  @Test
  public final void testXforwardedForHeader() throws Throwable {
    ReqAndChan rc = mockReq("http://es/index1/_search?q=item.getName():fishingpole&size=200", "1.1.1.1", "", "", 0, Method.POST, "9.9.9.9");
    BlockExitResult res = acl.check(rc.r, rc.c);
    assertTrue(res.isMatch());
    assertTrue(res.getBlock().getPolicy() == Block.Policy.ALLOW);
    assertEquals(res.getBlock().getName(), "1");
  }
}