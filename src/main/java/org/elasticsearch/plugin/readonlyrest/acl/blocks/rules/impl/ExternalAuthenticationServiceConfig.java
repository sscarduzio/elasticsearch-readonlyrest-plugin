package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.clients.ExternalAuthenticationServiceClient;
import org.elasticsearch.plugin.readonlyrest.clients.ExternalAuthenticationServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import static org.elasticsearch.plugin.readonlyrest.clients.CachedExternalAuthenticationServiceClient.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class ExternalAuthenticationServiceConfig {

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_AUTHENTICATION_ENDPOINT = "authentication_endpoint";
  private static String ATTRIBUTE_SUCCESS_STATUS_CODE = "success_status_code";

  private final String name;
  private final ExternalAuthenticationServiceClient client;

  private ExternalAuthenticationServiceConfig(String name,
                                              ExternalAuthenticationServiceClient client) {
    this.name = name;
    this.client = client;
  }

  public static ExternalAuthenticationServiceConfig fromSettings(Settings settings) throws ConfigMalformedException {
    ExternalAuthenticationServiceClient client = new ExternalAuthenticationServiceHttpClient(
        requiredAttributeValue(ATTRIBUTE_AUTHENTICATION_ENDPOINT, settings, ConfigReaderHelper.toUri()),
        requiredAttributeValue(ATTRIBUTE_SUCCESS_STATUS_CODE, settings, Integer::valueOf)
    );
    return new ExternalAuthenticationServiceConfig(
        requiredAttributeValue(ATTRIBUTE_NAME, settings),
        wrapInCacheIfCacheIsEnabled(settings, client)
    );
  }

  public String getName() {
    return name;
  }

  public ExternalAuthenticationServiceClient getClient() {
    return client;
  }
}
