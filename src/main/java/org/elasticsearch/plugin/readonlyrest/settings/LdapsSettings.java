package org.elasticsearch.plugin.readonlyrest.settings;

import com.google.common.collect.Lists;
import org.jooq.lambda.Seq;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class LdapsSettings {

  private static final String ATTRIBUTE_NAME = "ldaps";

  private final Map<String, LdapSettings> ldapSettingsMap;

  @SuppressWarnings("unchecked")
  static LdapsSettings from(RawSettings data) {
    return data.listOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> LdapSettings.from(new RawSettings((Map<String, ?>) l)))
                .collect(Collectors.toList())
        )
        .map(LdapsSettings::new)
        .orElse(new LdapsSettings(Lists.newArrayList()));
  }

  private LdapsSettings(List<LdapSettings> ldapSettings) {
    validate(ldapSettings);
    this.ldapSettingsMap = seq(ldapSettings).toMap(LdapSettings::getName, Function.identity());
  }

  public LdapSettings get(String name) {
    if (!ldapSettingsMap.containsKey(name))
      throw new ConfigMalformedException("Cannot find LDAP definition with name '" + name + "'");
    return ldapSettingsMap.get(name);
  }

  private void validate(List<LdapSettings> ldapSettings) {
    List<String> names = seq(ldapSettings).map(LdapSettings::getName).collect(Collectors.toList());
    if(names.stream().distinct().count() != names.size()) {
      throw new ConfigMalformedException("Duplicated LDAP name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
