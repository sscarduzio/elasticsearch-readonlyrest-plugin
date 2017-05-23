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
package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.Maps;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RawSettings {

  private final Map<String, ?> raw;

  public RawSettings(Map<String, ?> raw) {
    this.raw = raw;
  }

  public static RawSettings empty() {
    return new RawSettings(Maps.newHashMap());
  }

  @SuppressWarnings("unchecked")
  public static RawSettings fromFile(File file) throws IOException {
    Yaml yaml = new Yaml();
    try (FileInputStream stream = new FileInputStream(file)) {
      Map<String, ?> parsedData = (Map<String, ?>) yaml.load(stream);
      return new RawSettings(parsedData);
    }
  }

  @SuppressWarnings("unchecked")
  public static RawSettings fromString(String yamlContent) {
    Yaml yaml = new Yaml();
    Map<String, ?> parsedData = (Map<String, ?>) yaml.load(yamlContent);
    return new RawSettings(parsedData);
  }

  public Set<String> getKeys() {
    return raw.keySet();
  }

  @SuppressWarnings("unchecked")
  public <T> Optional<T> opt(String attr) {
    return Optional.ofNullable((T) raw.get(attr));
  }

  @SuppressWarnings("unchecked")
  public <T> T req(String attr) {
    Object val = raw.get(attr);
    if (val == null) throw new SettingsMalformedException("Could not find required attribute '" + attr + "'");
    return (T) val;
  }

  public Optional<Boolean> booleanOpt(String attr) {
    return opt(attr);
  }

  public Boolean booleanReq(String attr) {
    return req(attr);
  }

  public Optional<String> stringOpt(String attr) {
    return opt(attr);
  }

  public String stringReq(String attr) {
    return req(attr);
  }

  public Optional<Integer> intOpt(String attr) {
    return opt(attr);
  }

  public Integer intReq(String attr) {
    return req(attr);
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
    return opt(attr).flatMap(obj -> ((Set<?>) obj).isEmpty() ? Optional.empty() : Optional.of((Set<?>) obj));
  }

  public Set<?> notEmptySetReq(String attr) {
    Object value = req(attr);
    HashSet<Object> set = new HashSet<>();
    if (value instanceof List<?>) {
      set.addAll((List<?>) value);
    } else if (value instanceof String) {
      set.add(value);
    }
    if (set.isEmpty()) throw new SettingsMalformedException("Set value of'" + attr + "' attribute cannot be empty");
    return set;
  }

  public Optional<URI> uriOpt(String attr) {
    return stringOpt(attr).flatMap(s -> {
      try {
        return Optional.of(new URI(s));
      } catch (URISyntaxException e) {
        return Optional.empty();
      }
    });
  }

  public URI uriReq(String attr) {
    try {
      return new URI(stringReq(attr));
    } catch (URISyntaxException e) {
      throw new SettingsMalformedException("Cannot convert '" + attr + "' to URI");
    }
  }

  public RawSettings inner(String attr) {
    return new RawSettings(req(attr));
  }

  @SuppressWarnings("unchecked")
  public Optional<RawSettings> innerOpt(String attr) {
    return opt(attr).map(r -> new RawSettings((Map<String, ?>) r));
  }

}
