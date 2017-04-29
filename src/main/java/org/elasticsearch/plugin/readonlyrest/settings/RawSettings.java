package org.elasticsearch.plugin.readonlyrest.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RawSettings {

  private final Map<String, ?> raw;

  public RawSettings(Map<String, ?> raw) {
    this.raw = raw;
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
    if(val == null) throw new ConfigMalformedException("Not find required attribute '" + attr + "'");
    return (T) val;
  }

  public Optional<Boolean> booleanOtp(String attr) {
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

  public Optional<List<?>> listOpt(String attr) {
    return opt(attr);
  }

  public List<?> listReq(String attr) {
    return req(attr);
  }
  
  public RawSettings inner(String attr) {
    return new RawSettings(req(attr));
  }

  @SuppressWarnings("unchecked")
  public Optional<RawSettings> innerOpt(String attr) {
    return opt(attr).map(r -> new RawSettings((Map<String, ?>) r));
  }

}
