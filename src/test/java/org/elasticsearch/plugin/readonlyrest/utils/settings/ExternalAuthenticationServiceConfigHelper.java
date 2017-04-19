package org.elasticsearch.plugin.readonlyrest.utils.settings;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;

import java.net.URI;

public class ExternalAuthenticationServiceConfigHelper {

  public static Settings create(String name, URI endpoint, int successStatusCode) {
    return Settings.builder()
        .put("external_authentication_service_configs.0.name", name)
        .put("external_authentication_service_configs.0.authentication_endpoint", endpoint.toString())
        .put("external_authentication_service_configs.0.success_status_code", successStatusCode)
        .build();
  }
}
