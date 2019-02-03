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
import tech.beshu.ror.settings.definitions.__old_RorKbnAuthDefinitionSettings;
import tech.beshu.ror.settings.definitions.__old_RorKbnAuthDefinitionSettingsCollection;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class __old_RorKbnAuthRuleSettings implements RuleSettings, AuthKeyProviderSettings {

  public static final String ATTRIBUTE_NAME = "ror_kbn_auth";

  private static final String JWT_NAME = "name";
  private static final String ROLES = "roles";

  private final __old_RorKbnAuthDefinitionSettings authSettings;
  private final Set<String> roles;

  private __old_RorKbnAuthRuleSettings(__old_RorKbnAuthDefinitionSettings settings, Set<String> roles) {
    this.authSettings = settings;
    this.roles = roles;
  }

  @SuppressWarnings("unchecked")
  public static __old_RorKbnAuthRuleSettings from(RawSettings settings, __old_RorKbnAuthDefinitionSettingsCollection jwtSettingsCollection) {
    String jwtName = settings.stringReq(JWT_NAME);
    Set<String> roles = (Set<String>) (settings.notEmptySetOpt(ROLES).orElse(Collections.emptySet()));
    return new __old_RorKbnAuthRuleSettings(
        jwtSettingsCollection.get(jwtName),
        roles
    );
  }

  public static __old_RorKbnAuthRuleSettings from(String jwtName, __old_RorKbnAuthDefinitionSettingsCollection jwtSettingsCollection) {
    return new __old_RorKbnAuthRuleSettings(
        jwtSettingsCollection.get(jwtName),
        Collections.emptySet()
    );
  }

  public byte[] getKey() {
    return authSettings.getKey();
  }

  public Optional<String> getAlgo() {
    return authSettings.getAlgo();
  }

  public Optional<String> getUserClaim() {
    return authSettings.getUserClaim();
  }

  public Optional<String> getRolesClaim() {
    return authSettings.getRolesClaim();
  }

  public String getHeaderName() {
    return authSettings.getHeaderName();
  }

  public Set<String> getRoles() {
    return roles;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}