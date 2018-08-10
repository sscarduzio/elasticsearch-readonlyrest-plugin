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
import tech.beshu.ror.settings.definitions.JwtAuthDefinitionSettings;
import tech.beshu.ror.settings.definitions.JwtAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.RorKbnAuthDefinitionSettings;
import tech.beshu.ror.settings.definitions.RorKbnAuthDefinitionSettingsCollection;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class RorKbnAuthRuleSettings implements RuleSettings, AuthKeyProviderSettings {

  public static final String ATTRIBUTE_NAME = "ror_kbn_auth";

  private static final String JWT_NAME = "name";
  private static final String ROLES = "roles";

  private final RorKbnAuthDefinitionSettings jwtAuthSettings;
  private final Set<String> roles;

  private RorKbnAuthRuleSettings(RorKbnAuthDefinitionSettings settings, Set<String> roles) {
    this.jwtAuthSettings = settings;
    this.roles = roles;
  }

  @SuppressWarnings("unchecked")
  public static RorKbnAuthRuleSettings from(RawSettings settings, RorKbnAuthDefinitionSettingsCollection jwtSettingsCollection) {
    String jwtName = settings.stringReq(JWT_NAME);
    Set<String> roles = (Set<String>) (settings.notEmptySetOpt(ROLES).orElse(Collections.emptySet()));
    return new RorKbnAuthRuleSettings(
      jwtSettingsCollection.get(jwtName),
      roles
    );
  }

  public static RorKbnAuthRuleSettings from(String jwtName, RorKbnAuthDefinitionSettingsCollection jwtSettingsCollection) {
    return new RorKbnAuthRuleSettings(
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

  public Set<String> getRoles() {
    return roles;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}