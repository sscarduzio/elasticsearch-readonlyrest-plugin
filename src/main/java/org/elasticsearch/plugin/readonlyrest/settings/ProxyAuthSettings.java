package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ProxyAuthSettings extends Settings {

  @JsonProperty("name")
  private String name;

  @JsonProperty("user_id_header")
  private String userIdHeader = "X-Forwarded-User";

  public String getName() {
    return name;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }

  @Override
  protected void validate() {
    if(name == null) {
      throw new ConfigMalformedException("'name' was not defined in one of proxy_auth_configs element");
    }
  }
}
