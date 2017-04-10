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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;

import java.net.URI;

public class UserGroupsProviderConfigHelper {

  public static Settings create(String name, URI groupsEndpoint, String authTokenName,
                                UserGroupProviderConfig.TokenPassingMethod method, String responseGroupsJsonPath) {
    return Settings.builder()
        .put("user_groups_providers.0.name", name)
        .put("user_groups_providers.0.groups_endpoint", groupsEndpoint.toString())
        .put("user_groups_providers.0.auth_token_name", authTokenName)
        .put("user_groups_providers.0.auth_token_passed_as", tokenPassingMethodToString(method))
        .put("user_groups_providers.0.response_groups_json_path", responseGroupsJsonPath)
        .build();
  }

  private static String tokenPassingMethodToString(UserGroupProviderConfig.TokenPassingMethod method) {
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
