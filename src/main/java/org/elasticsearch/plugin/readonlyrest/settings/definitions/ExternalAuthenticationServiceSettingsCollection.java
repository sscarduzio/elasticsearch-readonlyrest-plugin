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
package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class ExternalAuthenticationServiceSettingsCollection {

  public static final String ATTRIBUTE_NAME = "external_authentication_service_configs";

  private final Map<String, ExternalAuthenticationServiceSettings> ExternalAuthenticationServiceSettingsMap;

  @SuppressWarnings("unchecked")
  public static ExternalAuthenticationServiceSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> new ExternalAuthenticationServiceSettings(new RawSettings((Map<String, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(ExternalAuthenticationServiceSettingsCollection::new)
        .orElse(new ExternalAuthenticationServiceSettingsCollection(Lists.newArrayList()));
  }

  private ExternalAuthenticationServiceSettingsCollection(
      List<ExternalAuthenticationServiceSettings> externalAuthenticationServiceSettings) {
    validate(externalAuthenticationServiceSettings);
    this.ExternalAuthenticationServiceSettingsMap = seq(externalAuthenticationServiceSettings)
        .toMap(ExternalAuthenticationServiceSettings::getName, Function.identity());
  }

  public ExternalAuthenticationServiceSettings get(String name) {
    if (!ExternalAuthenticationServiceSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find External Authentication Service definition with name '" + name + "'");
    return ExternalAuthenticationServiceSettingsMap.get(name);
  }

  private void validate(List<ExternalAuthenticationServiceSettings> externalAuthenticationServiceSettings) {
    List<String> names = seq(externalAuthenticationServiceSettings)
        .map(ExternalAuthenticationServiceSettings::getName)
        .collect(Collectors.toList());
    if(names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated LDAP name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
