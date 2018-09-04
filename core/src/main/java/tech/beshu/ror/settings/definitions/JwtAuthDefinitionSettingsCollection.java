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

package tech.beshu.ror.settings.definitions;

import com.google.common.collect.Lists;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

/**
 * @author Datasweet <contact@datasweet.fr>
 */
public class JwtAuthDefinitionSettingsCollection {
  private static final String ATTRIBUTE_NAME = "jwt";

  private final Map<String, JwtAuthDefinitionSettings> jwtAuthConfigsSettingsMap;

  private JwtAuthDefinitionSettingsCollection(List<JwtAuthDefinitionSettings> jwtAuthConfigsSettings) {
    validate(jwtAuthConfigsSettings);
    this.jwtAuthConfigsSettingsMap = seq(jwtAuthConfigsSettings).toMap(JwtAuthDefinitionSettings::getName, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static JwtAuthDefinitionSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list -> list.stream().map(l -> new JwtAuthDefinitionSettings(new RawSettings((Map<String, ?>) l, data.getLogger())))
        .collect(Collectors.toList()))
      .map(JwtAuthDefinitionSettingsCollection::new)
      .orElse(new JwtAuthDefinitionSettingsCollection(Lists.newArrayList()));
  }

  public JwtAuthDefinitionSettings get(String name) {
    if (!jwtAuthConfigsSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find Jwt Auth Config definition with name '" + name + "'");
    return jwtAuthConfigsSettingsMap.get(name);
  }

  private void validate(List<JwtAuthDefinitionSettings> jwtAuthConfigsSettings) {
    List<String> names = seq(jwtAuthConfigsSettings)
      .map(JwtAuthDefinitionSettings::getName)
      .collect(Collectors.toList());
    if (names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated Jwt Auth Config name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}