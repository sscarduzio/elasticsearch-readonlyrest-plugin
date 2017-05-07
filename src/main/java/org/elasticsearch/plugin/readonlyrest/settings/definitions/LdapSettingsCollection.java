package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jooq.lambda.Seq.seq;

public class LdapSettingsCollection {

  public static final String ATTRIBUTE_NAME = "ldaps";

  private final Map<String, LdapSettings> ldapSettingsMap;

  @SuppressWarnings("unchecked")
  public static LdapSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
        .map(list ->
            list.stream()
                .map(l -> {
                  RawSettings s = new RawSettings((Map<String, ?>) l);
                  return GroupsProviderLdapSettings.canBeCreated(s)
                      ? new GroupsProviderLdapSettings(s)
                      : new AuthenticationLdapSettings(s);
                })
                .collect(Collectors.toList())
        )
        .map(LdapSettingsCollection::new)
        .orElse(new LdapSettingsCollection(Lists.newArrayList()));
  }

  private LdapSettingsCollection(List<LdapSettings> ldapSettings) {
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
    if (names.stream().distinct().count() != names.size()) {
      throw new ConfigMalformedException("Duplicated LDAP name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
