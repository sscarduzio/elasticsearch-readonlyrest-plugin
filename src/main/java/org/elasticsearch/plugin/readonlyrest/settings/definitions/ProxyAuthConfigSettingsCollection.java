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
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class ProxyAuthConfigSettingsCollection {

  public static final String ATTRIBUTE_NAME = "proxy_auth_configs";

  private final Map<String, ProxyAuthConfigSettings> proxyAuthConfigsSettingsMap;

  @SuppressWarnings("unchecked")
  public static ProxyAuthConfigSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> new ProxyAuthConfigSettings(new RawSettings((Map<String, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(ProxyAuthConfigSettingsCollection::new)
        .orElse(new ProxyAuthConfigSettingsCollection(Lists.newArrayList()));
  }

  private ProxyAuthConfigSettingsCollection(List<ProxyAuthConfigSettings> proxyAuthConfigsSettings) {
    validate(proxyAuthConfigsSettings);
    this.proxyAuthConfigsSettingsMap = seq(proxyAuthConfigsSettings).toMap(ProxyAuthConfigSettings::getName, Function.identity());
  }

  public ProxyAuthConfigSettings get(String name) {
    if (!proxyAuthConfigsSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find Proxy Auth Config definition with name '" + name + "'");
    return proxyAuthConfigsSettingsMap.get(name);
  }

  private void validate(List<ProxyAuthConfigSettings> proxyAuthConfigsSettings) {
    List<String> names = seq(proxyAuthConfigsSettings).map(ProxyAuthConfigSettings::getName).collect(Collectors.toList());
    if(names.stream().distinct().count() != names.size()) {
      throw new ConfigMalformedException("Duplicated Proxy Auth Config name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
