package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.net.URI;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class ExternalAuthenticationServiceConfig {

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_AUTHENTICATION_ENDPOINT = "authentication_endpoint";
  private static String ATTRIBUTE_SUCCESS_STATUS_CODE = "success_status_code";

  private final String name;
  private final URI endpoint;
  private final int successStatusCode;

  private ExternalAuthenticationServiceConfig(String name,
                                              URI endpoint,
                                              int successStatusCode) {
    this.name = name;
    this.endpoint = endpoint;
    this.successStatusCode = successStatusCode;
  }

  public static ExternalAuthenticationServiceConfig fromSettings(Settings settings) throws ConfigMalformedException {
    return new ExternalAuthenticationServiceConfig(
        requiredAttributeValue(ATTRIBUTE_NAME, settings),
        requiredAttributeValue(ATTRIBUTE_AUTHENTICATION_ENDPOINT, settings, ConfigReaderHelper.toUri()),
        requiredAttributeValue(ATTRIBUTE_SUCCESS_STATUS_CODE, settings, Integer::valueOf)
    );
  }

  public String getName() {
    return name;
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public int getSuccessStatusCode() {
    return successStatusCode;
  }
}
