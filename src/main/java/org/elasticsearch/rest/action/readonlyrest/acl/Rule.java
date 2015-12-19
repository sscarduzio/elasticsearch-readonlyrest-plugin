package org.elasticsearch.rest.action.readonlyrest.acl;

import java.net.UnknownHostException;

import java.util.List;
import java.util.regex.Pattern;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.ConfigurationHelper;
import org.elasticsearch.rest.action.readonlyrest.IPMask;

public class Rule {

  public enum Type {
    ALLOW, FORBID;

    public static String valuesString(){
      StringBuilder sb = new StringBuilder();
      for(Type v: values()){
        sb.append(v.toString()).append(",");
      }
      sb.deleteCharAt(sb.length()-1);
      return sb.toString();
    }
  }

  String               name;
  Type                 type;
  Pattern              uri_re;
  Integer              maxBodyLenght;
  List<String>         addresses;
  List<String>         apiKeys;
  String               authKey;
  private List<Method> methods;
  String               stringRepresentation;

  public Rule(String name, Type type, Pattern uri_re, Integer bodyLenght, List<String> addresses, List<String> apiKeys, String authKey, List<Method> methods, String toString) {
    this.name = name;
    this.type = type;
    this.uri_re = uri_re;
    this.maxBodyLenght = bodyLenght;
    this.addresses = addresses;
    this.apiKeys = apiKeys;
    this.authKey= authKey;
    this.methods = methods;
    this.stringRepresentation = toString;
  }

  public static Rule build(Settings s) {
    List<String> hosts = null;
    String[] a = s.getAsArray("hosts");
    if (a != null && a.length > 0) {
      hosts = Lists.newArrayList();
      for (int i=0; i < a.length; i++) {
        if(!ConfigurationHelper.isNullOrEmpty(a[i])) {
          hosts.add(a[i].trim());
        }
      }
    }

    a = s.getAsArray("api_keys");
    List<String> apiKeys = null;
    if (a != null && a.length > 0) {
      apiKeys = Lists.newArrayList();
      for (int i=0; i < a.length; i++) {
        if(!ConfigurationHelper.isNullOrEmpty(a[i])) {
          apiKeys.add(a[i].trim());
        }
      }
    }

    String authKey = s.get("auth_key");
    if(authKey != null && authKey.trim().length() > 0) {
      authKey = Base64.encodeBytes(authKey.getBytes(Charsets.UTF_8));
    }

    a = s.getAsArray("methods");
    List<Method> methods = null;
    if (a != null && a.length > 0) {
      try {
        for (String string : a) {
          Method m = Method.valueOf(string.trim().toUpperCase());
          if (methods == null) {
            methods = Lists.newArrayList();
          }
          methods.add(m);
        }
      }
      catch(Throwable t){
        throw new RuleConfigurationError("Invalid HTTP method found in configuration " + a, t);
      }
    }

    Pattern uri_re = null;
    String tmp = s.get("uri_re");
    if (!ConfigurationHelper.isNullOrEmpty(tmp)) {
      uri_re = Pattern.compile(tmp.trim());
    }
    String name = s.get("name");

    String sType = s.get("type");
    if(sType == null) {
      throw new RuleConfigurationError("The field \"type\" is mandatory and should be either of " + Type.valuesString() + ". If this field is correct, check the YAML indentation is correct.", null);
    }
    Rule.Type type = Type.valueOf(sType.toUpperCase());
    Integer maxBodyLength = s.getAsInt("maxBodyLength", null);

    if ((!ConfigurationHelper.isNullOrEmpty(name) && type != null) &&
        (uri_re != null || maxBodyLength != null || hosts != null || apiKeys != null || authKey != null|| methods != null)) {
      return new Rule(name.trim(), type, uri_re, maxBodyLength, hosts, apiKeys, authKey, methods, s.toDelimitedString(' '));
    }
    throw new RuleConfigurationError("insufficient or invalid configuration for rule: '" + name + "'", null);

  }

  @Override
  public String toString() {
    return stringRepresentation;
  }

  /*
   * All "matches" methods should return true if no explicit condition was configured
   */
  public boolean matchesAddress(String address) {
    if (addresses == null) {
      return true;
    }

    for (String allowedAddress : addresses) {
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

  public boolean matchesApiKey(String apiKey) {
    if (apiKeys == null) {
      return true;
    }
    return apiKeys.contains(apiKey);
  }

  protected boolean matchesAuthKey(String authHeader) {
    if (authKey == null) {
      return true;
    }
    String val = authHeader.trim();
    if(val.length() == 0) {
      return false;
    }
    return val.equals(authKey);
  }

  public boolean matchesMaxBodyLength(Integer len) {
    if (maxBodyLenght == null) {
      return true;
    }
    return len <= maxBodyLenght;
  }

  public boolean matchesUriRe(String uri) {
    if (uri_re == null) {
      return true;
    }
    return uri_re.matcher(uri).find();

  }

  public boolean matchesMethods(Method method) {
    if (methods == null){
      return true;
    }
    return methods.contains(method);
  }
}
