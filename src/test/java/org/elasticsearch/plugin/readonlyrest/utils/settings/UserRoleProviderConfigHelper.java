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
