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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.util.Objects;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class ProxyAuthConfig {

  public static final ProxyAuthConfig DEFAULT = new ProxyAuthConfig("", "X-Forwarded-User");
  private static final String ATTRIBUTE_NAME = "name";
  private static final String ATTRIBUTE_USER_ID_HEADER = "user_id_header";

  private final String name;
  private final String userIdHeader;

  private ProxyAuthConfig(String name, String userIdHeader) {
    this.name = name;
    this.userIdHeader = userIdHeader;
  }

  public static ProxyAuthConfig fromSettings(Settings settings) throws ConfigMalformedException {
    String name = requiredAttributeValue(ATTRIBUTE_NAME, settings);
    if (Objects.equals(name, DEFAULT.getName()))
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
