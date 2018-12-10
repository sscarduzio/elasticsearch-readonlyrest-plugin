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
package tech.beshu.ror.settings.rules;

import java.time.Duration;

public class AuthKeyUnixRuleSettings extends __old_AuthKeyRuleSettings implements CacheSettings {

  public static final String ATTRIBUTE_NAME = "auth_key_unix";
  public static final String ATTRIBUTE_AUTH_CACHE_TTL = "auth_cache_ttl_sec";

  public static final Integer DEFAULT_CACHE_TTL = 10;
  private final Duration ttl;

  public AuthKeyUnixRuleSettings(String authKey, Duration ttl) {
    super(authKey);
    this.ttl = ttl;
  }

  public static AuthKeyUnixRuleSettings from(String authKey, Duration ttl) {
    return new AuthKeyUnixRuleSettings(authKey, ttl);
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

  @Override
  public Duration getCacheTtl() {
    return ttl;
  }
}
