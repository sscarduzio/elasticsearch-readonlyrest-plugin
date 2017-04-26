package org.elasticsearch.plugin.readonlyrest.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

public class RawSettings {

  private final LinkedHashMap<?, ?> raw;

  public RawSettings(LinkedHashMap<?, ?> raw) {
    this.raw = raw;
  }

  @SuppressWarnings("unchecked")
  protected <T> Optional<T> opt(String attr) {
    return Optional.ofNullable((T) raw.get(attr));
  }

  @SuppressWarnings("unchecked")
  protected <T> T req(String attr) {
    Object val = raw.get(attr);
    if(val == null) throw new ConfigMalformedException("Not find required attribute '" + attr + "'");
    return (T) val;
  }

  protected Optional<Boolean> booleanOtp(String attr) {
    return opt(attr);
  }

  protected Boolean booleanReq(String attr) {
    return req(attr);
  }

  protected Optional<String> stringOpt(String attr) {
    return opt(attr);
  }

  protected String stringReq(String attr) {
    return req(attr);
  }

  protected Optional<List<?>> listOpt(String attr) {
    return opt(attr);
  }

  protected List<?> listReq(String attr) {
    return req(attr);
  }
}
