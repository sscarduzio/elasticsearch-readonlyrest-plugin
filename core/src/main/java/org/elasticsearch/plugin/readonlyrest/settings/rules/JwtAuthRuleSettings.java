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
package org.elasticsearch.plugin.readonlyrest.settings.rules;

import com.google.common.base.Strings;
import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;

import java.util.Optional;

public class JwtAuthRuleSettings implements RuleSettings, AuthKeyProviderSettings {

  public static final String ATTRIBUTE_NAME = "jwt_auth";
  public static final String SIGNATURE_KEY = "signature_key";
  public static final String USER_CLAIM = "user_claim";

  private final byte[] key;
  private final Optional<String> userClaim;

  private JwtAuthRuleSettings(String key, Optional<String> userClaim) {
    if (Strings.isNullOrEmpty(key))
      throw new SettingsMalformedException(
        "Attribute '" + SIGNATURE_KEY + "' shall not evaluate to an empty string");
    this.key = key.getBytes();
    this.userClaim = userClaim;
  }

  public static JwtAuthRuleSettings from(RawSettings settings) {
    return new JwtAuthRuleSettings(
      evalPrefixedSignatureKey(ensureString(settings, SIGNATURE_KEY)),
      settings.opt(USER_CLAIM)
    );
  }

  private static String ensureString(RawSettings settings, String key) {
    Object value = settings.req(key);
    if (value instanceof String) return (String) value;
    else throw new SettingsMalformedException(
      "Attribute '" + key + "' must be a string; if it looks like a number try adding quotation marks");
  }

  private static String evalPrefixedSignatureKey(String s) {
    if (s.startsWith("text:"))
      return s.substring(5);
    else if (s.startsWith("env:"))
      return System.getenv(s.substring(4));
    else return s;
  }

  public byte[] getKey() {
    return key;
  }

  public Optional<String> getUserClaim() {
    return userClaim;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}