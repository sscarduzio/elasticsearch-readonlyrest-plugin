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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceHttpClient.TokenPassingMethod;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.util.function.Function;

import static org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.CachedGroupsProviderServiceClient.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class UserGroupProviderConfig {

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_GROUP_ENDPOINT = "groups_endpoint";
  private static String ATTRIBUTE_AUTH_TOKEN_NAME = "auth_token_name";
  private static String ATTRIBUTE_AUTH_TOKEN_PASSED_AS = "auth_token_passed_as";
  private static String ATTRIBUTE_RESPONSE_GROUPS_JSON_PATH = "response_groups_json_path";

  private final String name;
  private final GroupsProviderServiceClient client;

  private UserGroupProviderConfig(String name,
                                  GroupsProviderServiceClient client) {
    this.name = name;
    this.client = client;
  }

  public static UserGroupProviderConfig fromSettings(Settings settings) throws ConfigMalformedException {
    String name = requiredAttributeValue(ATTRIBUTE_NAME, settings);
    GroupsProviderServiceClient client = new GroupsProviderServiceHttpClient(name,
        requiredAttributeValue(ATTRIBUTE_GROUP_ENDPOINT, settings, ConfigReaderHelper.toUri()),
        requiredAttributeValue(ATTRIBUTE_AUTH_TOKEN_NAME, settings),
        requiredAttributeValue(ATTRIBUTE_AUTH_TOKEN_PASSED_AS, settings, fromStringToTokenPassingMethod()),
        requiredAttributeValue(ATTRIBUTE_RESPONSE_GROUPS_JSON_PATH, settings)
    );
    return new UserGroupProviderConfig(name, wrapInCacheIfCacheIsEnabled(settings, client));
  }

  private static Function<String, TokenPassingMethod> fromStringToTokenPassingMethod() {
    return value -> {
      switch (value) {
        case "QUERY_PARAM":
          return TokenPassingMethod.QUERY;
        case "HEADER":
          return TokenPassingMethod.HEADER;
        default:
          throw new ConfigMalformedException("Unknown token passing method: '" + value + "'");
      }
    };
  }

  public String getName() {
    return name;
  }

  public GroupsProviderServiceClient getClient() {
    return client;
  }

}
