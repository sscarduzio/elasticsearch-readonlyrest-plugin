package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.util.Objects;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class ProxyAuthConfig {

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_USER_ID_HEADER = "user_id_header";

  private final String name;
  private final String userIdHeader;

  public static ProxyAuthConfig DEFAULT = new ProxyAuthConfig("", "X-Forwarded-User");

  private ProxyAuthConfig(String name, String userIdHeader) {
    this.name = name;
    this.userIdHeader = userIdHeader;
  }

  public static ProxyAuthConfig fromSettings(Settings settings) throws ConfigMalformedException {
    String name = requiredAttributeValue(ATTRIBUTE_NAME, settings);
    if(Objects.equals(name, DEFAULT.getName()))
      throw new ConfigMalformedException("Wrong Proxy Auth name");

    return new ProxyAuthConfig(name,
        requiredAttributeValue(ATTRIBUTE_USER_ID_HEADER, settings)
    );
  }

  public String getName() {
    return name;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }
}
