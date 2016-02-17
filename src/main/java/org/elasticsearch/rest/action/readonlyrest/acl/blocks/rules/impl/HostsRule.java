package org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import com.google.common.net.InternetDomainName;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.netty.NettyHttpChannel;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.readonlyrest.ConfigurationHelper;
import org.elasticsearch.rest.action.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.IPMask;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.rest.action.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.jboss.netty.channel.socket.SocketChannel;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class HostsRule extends Rule {
  /*
   * A regular expression to match the various representations of "localhost"
   */
  private final static Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");

  private final static String LOCALHOST = "127.0.0.1";

  private List<String> allowedAddresses;
  private Boolean acceptXForwardedForHeader;
  public HostsRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    acceptXForwardedForHeader = s.getAsBoolean("accept_x-forwarded-for_header", false);
    String[] a = s.getAsArray("hosts");
    if (a != null && a.length > 0) {
      allowedAddresses = Lists.newArrayList();
      for (int i = 0; i < a.length; i++) {
        if (!ConfigurationHelper.isNullOrEmpty(a[i])) {
          try {
            IPMask.getIPMask(a[i]);
          }
          catch (Exception e) {
            if(!InternetDomainName.isValid(a[i])){
              throw new RuleConfigurationError("invalid address", e);
            }
          }
          allowedAddresses.add(a[i].trim());
        }
      }
    } else {
      throw new RuleNotConfiguredException();
    }
  }

  private static String getXForwardedForHeader(RestRequest request) {
    if (!ConfigurationHelper.isNullOrEmpty(request.header("X-Forwarded-For"))) {
      String[] parts = request.header("X-Forwarded-For").split(",");
      if (!ConfigurationHelper.isNullOrEmpty(parts[0])) {
        return parts[0];
      }
    }
    return null;
  }

  public static String getAddress(RestChannel channel) {
    String remoteHost = null;

    try {
      NettyHttpChannel obj = (NettyHttpChannel) channel;
      Field f = obj.getClass().getDeclaredField("channel");
      f.setAccessible(true);
      SocketChannel sc = (SocketChannel) f.get(obj);
      InetSocketAddress remoteHostAddr = sc.getRemoteAddress();
      remoteHost = remoteHostAddr.getAddress().getHostAddress();
      // Make sure we recognize localhost even when IPV6 is involved
      if (localhostRe.matcher(remoteHost).find()) {
        remoteHost = LOCALHOST;
      }
    } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }
    return remoteHost;
  }
  /*
   * All "matches" methods should return true if no explicit condition was configured
   */

  private boolean matchesAddress(String address, String xForwardedForHeader) {

    if (allowedAddresses == null) {
      return true;
    }

    if (acceptXForwardedForHeader && xForwardedForHeader != null) {
      // Give it a try with the header
      if (matchesAddress(xForwardedForHeader, null)) return true;
    }
    for (String allowedAddress : allowedAddresses) {
      if (allowedAddress.indexOf("/") > 0) {
        try {
          IPMask ipmask = IPMask.getIPMask(allowedAddress);
          if (ipmask.matches(address)) {
            return true;
          }
        } catch (UnknownHostException e) {
        }
      }
      if (allowedAddress.equals(address)) {
        return true;
      }
    }

    return false;
  }

  public RuleExitResult match(RestRequest request, RestChannel channel) {
    boolean res = matchesAddress(getAddress(channel), getXForwardedForHeader(request));
    return res ? MATCH : NO_MATCH;
  }
}
