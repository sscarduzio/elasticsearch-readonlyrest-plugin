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

public class __old_ProxyAuthDefinitionSettingsCollection {

  public static final String ATTRIBUTE_NAME = "proxy_auth_configs";

  private final Map<String, __old_ProxyAuthDefinitionSettings> proxyAuthConfigsSettingsMap;

  private __old_ProxyAuthDefinitionSettingsCollection(List<__old_ProxyAuthDefinitionSettings> proxyAuthConfigsSettings) {
    validate(proxyAuthConfigsSettings);
    this.proxyAuthConfigsSettingsMap = seq(proxyAuthConfigsSettings).toMap(__old_ProxyAuthDefinitionSettings::getName, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static __old_ProxyAuthDefinitionSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list ->
             list.stream()
               .map(l -> new __old_ProxyAuthDefinitionSettings(new RawSettings((Map<String, ?>) l, data.getLogger())))
               .collect(Collectors.toList())
      )
      .map(__old_ProxyAuthDefinitionSettingsCollection::new)
      .orElse(new __old_ProxyAuthDefinitionSettingsCollection(Lists.newArrayList()));
  }

  public __old_ProxyAuthDefinitionSettings get(String name) {
    if (!proxyAuthConfigsSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find Proxy Auth Config definition with name '" + name + "'");
    return proxyAuthConfigsSettingsMap.get(name);
  }

  private void validate(List<__old_ProxyAuthDefinitionSettings> proxyAuthConfigsSettings) {
    List<String> names = seq(proxyAuthConfigsSettings).map(__old_ProxyAuthDefinitionSettings::getName).collect(Collectors.toList());
    if (names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated Proxy Auth Config name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
