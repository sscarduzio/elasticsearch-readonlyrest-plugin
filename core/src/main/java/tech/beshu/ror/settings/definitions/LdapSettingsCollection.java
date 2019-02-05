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

public class LdapSettingsCollection {

  public static final String ATTRIBUTE_NAME = "ldaps";

  private final Map<String, LdapSettings> ldapSettingsMap;

  private LdapSettingsCollection(List<LdapSettings> ldapSettings) {
    validate(ldapSettings);
    this.ldapSettingsMap = seq(ldapSettings).toMap(LdapSettings::getName, Function.identity());
  }

  @SuppressWarnings("unchecked")
  public static LdapSettingsCollection from(RawSettings data) {
    return data.notEmptyListOpt(ATTRIBUTE_NAME)
      .map(list ->
             list.stream()
               .map(l -> {
                 RawSettings s = new RawSettings((Map<String, ?>) l, data.getLogger());
                 return (LdapSettings) (GroupsProviderLdapSettings.canBeCreated(s)
                   ? new GroupsProviderLdapSettings(s)
                   : new AuthenticationLdapSettings(s));
               })
               .collect(Collectors.toList())
      )
      .map(LdapSettingsCollection::new)
      .orElse(new LdapSettingsCollection(Lists.newArrayList()));
  }

  public LdapSettings get(String name) {
    if (!ldapSettingsMap.containsKey(name))
      throw new SettingsMalformedException("Cannot find LDAP definition with name '" + name + "'");
    return ldapSettingsMap.get(name);
  }

  private void validate(List<LdapSettings> ldapSettings) {
    List<String> names = seq(ldapSettings).map(LdapSettings::getName).collect(Collectors.toList());
    if (names.stream().distinct().count() != names.size()) {
      throw new SettingsMalformedException("Duplicated LDAP name in '" + ATTRIBUTE_NAME + "' section");
    }
  }
}
