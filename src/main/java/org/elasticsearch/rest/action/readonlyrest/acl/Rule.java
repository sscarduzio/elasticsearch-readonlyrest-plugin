package org.elasticsearch.rest.action.readonlyrest.acl;

import java.util.List;
import java.util.regex.Pattern;

import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.action.readonlyrest.ConfigurationHelper;

public class Rule {

  public enum Type {
    ALLOW, FORBID;
  }

  String               name;
  Type                 type;
  Pattern              uri_re;
  Integer              maxBodyLenght;
  List<String>         addresses;
  private List<Method> methods;
  String               stringRepresentation;

  public Rule(String name, Type type, Pattern uri_re, Integer bodyLenght, List<String> addresses, List<Method> methods, String toString) {
    this.name = name;
    this.type = type;
    this.uri_re = uri_re;
    this.maxBodyLenght = bodyLenght;
    this.addresses = addresses;
    this.methods = methods;
    this.stringRepresentation = toString;
  }

  public static Rule build(Settings s) throws Exception {
    List<String> hosts = null;
    String[] a = s.getAsArray("hosts");
    if (a != null && a.length > 0) {
      hosts = Lists.newArrayList(a);
      for (String address : a) {
        address = address.trim();
      }
    }

    a = s.getAsArray("methods");
    List<Method> methods = null;
    if (a != null && a.length > 0) {
      for (String string : a) {
        Method m = Method.valueOf(string.trim().toUpperCase());
        if (methods == null) {
          methods = Lists.newArrayList();
        }
        methods.add(m);
      }
    }

    Pattern uri_re = null;
    String tmp = s.get("uri_re");
    if (!ConfigurationHelper.isNullOrEmpty(tmp)) {
      uri_re = Pattern.compile(tmp.trim());
    }
    String name = s.get("name");
    Rule.Type type = Type.valueOf(s.get("type").toUpperCase());
    Integer maxBodyLenght = s.getAsInt("maxBodyLength", null);
    if ((!ConfigurationHelper.isNullOrEmpty(name) && type != null) && 
        (uri_re != null || maxBodyLenght != null || hosts != null || methods != null)) {
      return new Rule(name.trim(), type, uri_re, maxBodyLenght, hosts, methods, s.toDelimitedString(' '));
    }
    throw new Exception("insufficient or invalid configuration for rule: '" + name + "'");

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
    return addresses.contains(address);
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

  public boolean mathesMethods(Method method) {
    if (methods == null){
      return true;
    }
    return methods.contains(method);
  }
}
