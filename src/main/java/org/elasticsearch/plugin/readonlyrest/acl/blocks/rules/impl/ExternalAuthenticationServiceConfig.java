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
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import static org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.CachedExternalAuthenticationServiceClient.wrapInCacheIfCacheIsEnabled;
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
