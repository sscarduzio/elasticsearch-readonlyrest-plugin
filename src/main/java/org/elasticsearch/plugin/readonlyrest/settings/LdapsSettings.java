package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LdapsSettings {

  private static final String ATTRIBUTE_NAME = "ldaps";

  private final Map<String, LdapSettings> ldapSettingsMap;

  static LdapsSettings from(RawSettings data) {
    return data.listOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> LdapSettings.from(new RawSettings((LinkedHashMap<?, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(LdapsSettings::new)
        .orElse(new LdapsSettings(Lists.newArrayList()));
  }

  private LdapsSettings(List<LdapSettings> ldapSettings) {
    // todo: validate uniqueness of name
    this.ldapSettingsMap = Maps.newHashMap();
  }

  public LdapSettings get(String name) {
    if (!ldapSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find LDAP definition with name '" + name + "'");
    return ldapSettingsMap.get(name);
  }
}
