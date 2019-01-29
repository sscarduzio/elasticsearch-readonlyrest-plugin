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

import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.__old_JwtAuthDefinitionSettings;
import tech.beshu.ror.settings.definitions.__old_JwtAuthDefinitionSettingsCollection;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class __old_JwtAuthRuleSettings implements RuleSettings, AuthKeyProviderSettings, CacheSettings, NamedSettings {

  public static final String ATTRIBUTE_NAME = "jwt_auth";

  private static final String JWT_NAME = "name";
  private static final String ROLES = "roles";

  private final __old_JwtAuthDefinitionSettings jwtAuthSettings;
  private final Set<String> roles;

  private __old_JwtAuthRuleSettings(__old_JwtAuthDefinitionSettings settings, Set<String> roles) {
    this.jwtAuthSettings = settings;
    this.roles = roles;
  }

  @SuppressWarnings("unchecked")
  public static __old_JwtAuthRuleSettings from(RawSettings settings, __old_JwtAuthDefinitionSettingsCollection jwtSettingsCollection) {
    String jwtName = settings.stringReq(JWT_NAME);
    Set<String> roles = (Set<String>) (settings.notEmptySetOpt(ROLES).orElse(Collections.emptySet()));
    return new __old_JwtAuthRuleSettings(
        jwtSettingsCollection.get(jwtName),
        roles
    );
  }

  public static __old_JwtAuthRuleSettings from(String jwtName, __old_JwtAuthDefinitionSettingsCollection jwtSettingsCollection) {
    return new __old_JwtAuthRuleSettings(
        jwtSettingsCollection.get(jwtName),
        Collections.emptySet()
    );
  }

  public byte[] getKey() {
    return jwtAuthSettings.getKey();
  }

  public Optional<String> getAlgo() {
    return jwtAuthSettings.getAlgo();
  }

  public Optional<String> getUserClaim() {
    return jwtAuthSettings.getUserClaim();
  }

  public Optional<String> getRolesClaim() {
    return jwtAuthSettings.getRolesClaim();
  }

  public String getHeaderName() {
    return jwtAuthSettings.getHeaderName();
  }

  public Optional<String> getExternalValidator() {
    return jwtAuthSettings.getExternalValidator();
  }

  public Set<String> getRoles() {
    return roles;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

  public boolean getExternalValidatorValidate() {
    return jwtAuthSettings.getExternalValidatorValidate();
  }

  public int getExternalValidatorSuccessStatusCode() {
    return jwtAuthSettings.getExternalValidatorSuccessStatusCode();
  }

  @Override
  public Duration getCacheTtl() {
    return jwtAuthSettings.getExternalValidatorCacheTtl();
  }
}