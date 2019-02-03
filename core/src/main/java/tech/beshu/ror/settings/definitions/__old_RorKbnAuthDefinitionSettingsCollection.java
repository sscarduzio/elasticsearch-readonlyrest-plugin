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
public class __old_RorKbnAuthDefinitionSettingsCollection {
  private static final String ATTRIBUTE_NAME = "ror_kbn";

  private final Map<String, __old_RorKbnAuthDefinitionSettings> jwtAuthConfigsSettingsMap;

  private __old_RorKbnAuthDefinitionSettingsCollection(List<__old_RorKbnAuthDefinitionSettings> jwtAuthConfigsSettings) {
    validate(jwtAuthConfigsSettings);
    this.jwtAuthConfigsSettingsMap = seq(jwtAuthConfigsSettings).toMap(__old_RorKbnAuthDefinitionSettings::getName, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static __old_RorKbnAuthDefinitionSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
               .map(list -> list.stream().map(l -> new __old_RorKbnAuthDefinitionSettings(new RawSettings((Map<String, ?>) l, data.getLogger())))
                                .collect(Collectors.toList()))
               .map(__old_RorKbnAuthDefinitionSettingsCollection::new)
               .orElse(new __old_RorKbnAuthDefinitionSettingsCollection(Lists.newArrayList()));
  }

  public __old_RorKbnAuthDefinitionSettings get(String name) {
    if (!jwtAuthConfigsSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find " + ATTRIBUTE_NAME + " Auth Config definition with name '" + name + "'");
    return jwtAuthConfigsSettingsMap.get(name);
  }

  private void validate(List<__old_RorKbnAuthDefinitionSettings> jwtAuthConfigsSettings) {
    List<String> names = seq(jwtAuthConfigsSettings)
        .map(__old_RorKbnAuthDefinitionSettings::getName)
        .collect(Collectors.toList());
    if (names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated " + ATTRIBUTE_NAME + " Auth Config name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}