package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

public class ProxyAuthConfigSettings {

  private static final String NAME = "name";
  private static final String USER_ID_HEADER = "user_id_header";

  private final String name;
  private final String userIdHeader;

  public ProxyAuthConfigSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);
    this.userIdHeader = settings.stringReq(USER_ID_HEADER);
  }

  public String getName() {
    return name;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }
}
