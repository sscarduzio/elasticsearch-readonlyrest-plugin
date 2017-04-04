package org.elasticsearch.plugin.readonlyrest.utils.settings;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig;

import java.net.URI;

public class UserRoleProviderConfigHelper {

  public static Settings create(String name, URI roleEndpoint, String authTokenName,
                                UserRoleProviderConfig.TokenPassingMethod method, String responseRoleJsonPath) {
    return Settings.builder()
        .put("user_role_providers.0.name", name)
        .put("user_role_providers.0.role_endpoint", roleEndpoint.toString())
        .put("user_role_providers.0.auth_token_name", authTokenName)
        .put("user_role_providers.0.auth_token_passed_as", tokenPassingMethodToString(method))
        .put("user_role_providers.0.response_roles_json_path", responseRoleJsonPath)
        .build();
  }

  private static String tokenPassingMethodToString(UserRoleProviderConfig.TokenPassingMethod method) {
    switch (method) {
      case QUERY:
        return "QUERY_PARAM";
      case HEADER:
        return "HEADER";
      default:
        throw new IllegalArgumentException("unknown token passing method");
    }
  }
}
