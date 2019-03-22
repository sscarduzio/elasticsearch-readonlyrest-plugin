/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.settings;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import cz.seznam.euphoria.shaded.guava.com.google.common.base.Joiner;
import tech.beshu.ror.shims.es.LoggerShim;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class RawSettings {

  private final Map<String, ?> raw;
  private final DocumentContext jpathContext;
  private final String rawYaml;
  private final LoggerShim logger;

  public RawSettings(String rawYaml, LoggerShim logger) {
    this.logger = logger;
    this.rawYaml = replaceEnvVars(rawYaml);
    this.raw = SettingsUtils.yaml2Map(rawYaml, logger);
    if (raw == null) {
      throw new SettingsMalformedException("Received null ROR settings: " + raw);
    }
    this.jpathContext = JsonPath.parse(raw);
  }

  public RawSettings(Map<String, ?> raw, LoggerShim logger) {
    this.logger = logger;
    this.rawYaml = replaceEnvVars(SettingsUtils.map2yaml(raw));
    this.raw = SettingsUtils.yaml2Map(rawYaml, logger);
    if (raw == null) {
      throw new SettingsMalformedException("Received null ROR settings: " + raw);
    }
    this.jpathContext = JsonPath.parse(this.raw);
  }

  private static String replaceEnvVars(String rawYaml) {
    String out = rawYaml;
    for (String key : System.getenv().keySet()) {
      out = out.replaceAll(Pattern.quote("${" + key + "}"), System.getenv(key));
    }
    return out;
  }

  static RawSettings fromMap(Map<String, ?> r, LoggerShim logger) {
    String syntheticYaml = SettingsUtils.map2yaml(r);
    return new RawSettings(syntheticYaml, logger);
  }

  public LoggerShim getLogger() {
    return logger;
  }

  public Set<String> getKeys() {
    return raw.keySet();
  }

  @SuppressWarnings("unchecked")
  public <T> Optional<T> opt(String attr) {

    Object val;
    try {
      val = jpathContext.read("$." + attr);
    }
    catch (Exception e) {
      val = null;
    }

    return Optional.ofNullable((T) val);
  }

  @SuppressWarnings("unchecked")
  public <T> T req(String attr) {
    //Object val = raw.resolve(attr);
    Object val;
    try {
      val = (T) jpathContext.read("$." + attr);
    }
    catch (Exception e) {
      val = null;
    }
    if (val == null) {
      throw new SettingsMalformedException("Could not find required attribute '" + attr + "' in file " + Joiner.on(",").withKeyValueSeparator(":").join(raw));
    }
    return (T) val;
  }

  public Optional<Boolean> booleanOpt(String attr) {
    return opt(attr).map(x -> {
      if (x instanceof String) {
        return Boolean.parseBoolean((String) x);
      }
      return (Boolean) x;
    });
  }

  public Boolean booleanReq(String attr) {
    return req(attr);
  }

  public Optional<String> stringOpt(String attr) {
    return opt(attr);
  }

  public String stringReq(String attr) {
    String s = req(attr);
    return s;
  }

  public Optional<Integer> intOpt(String attr) {
    return opt(attr).map(s -> {
      if (s instanceof String) {
        return Integer.parseInt((String) s);
      }
      if (s instanceof Double) {
        return ((Double) s).intValue();
      }
      return (Integer) s;
    });
  }

  public Integer intReq(String attr) {
    return Integer.parseInt(req(attr));
  }

  public Optional<List<?>> notEmptyListOpt(String attr) {
    return opt(attr).flatMap(obj -> ((List<?>) obj).isEmpty() ? Optional.empty() : Optional.of((List<?>) obj));
  }

  public List<?> notEmptyListReq(String attr) {
    List<?> nel = req(attr);
    if (nel.isEmpty()) throw new SettingsMalformedException("List value of'" + attr + "' attribute cannot be empty");
    return nel;
  }

  public Optional<Set<?>> notEmptySetOpt(String attr) {
    return opt(attr).flatMap(value -> {
      HashSet<Object> set = new HashSet<>();
      if (value instanceof List<?>) {
        List<?> l = (List<?>) value;
        set.addAll(l);
        if (set.size() < l.size()) {
          throw new SettingsMalformedException("Set value of '" + attr + "' attribute cannot contain duplicates");
        }
      }
      else if (value instanceof String) {
        set.add(value);
      }
      if (set.isEmpty()) return Optional.empty();
      return Optional.of(set);
    });
  }

  public Set<?> notEmptySetReq(String attr) {
    Object value = req(attr);
    HashSet<Object> set = new HashSet<>();
    if (value instanceof List<?>) {
      List<?> l = (List<?>) value;
      set.addAll(l);
      if (set.size() < l.size()) {
        throw new SettingsMalformedException("Set value of '" + attr + "' attribute cannot contain duplicates");
      }
    }
    else if (value instanceof String) {
      set.add(value);
    }
    if (set.isEmpty()) throw new SettingsMalformedException("Set value of '" + attr + "' attribute cannot be empty");
    return set;
  }

  public Optional<URI> uriOpt(String attr) {
    return stringOpt(attr).flatMap(s -> {
      try {
        return Optional.of(new URI(s));
      }
      catch (URISyntaxException e) {
        return Optional.empty();
      }
    });
  }

  public URI uriReq(String attr) {
    try {
      return new URI(stringReq(attr));
    }
    catch (URISyntaxException e) {
      throw new SettingsMalformedException("Cannot convert '" + attr + "' to URI");
    }
  }

  public RawSettings inner(String attr) {
    return new RawSettings((Map<String, ?>) req(attr), logger);
  }

  @SuppressWarnings("unchecked")
  public Optional<RawSettings> innerOpt(String attr) {
    return opt(attr).map(r -> RawSettings.fromMap((Map<String, ?>) r, logger));
  }

  public Map<String, ?> asMap() {
    return raw;
  }


  public String yaml() {
    return rawYaml;
  }

  @Override
  public String toString() {
    return rawYaml;
  }
}
