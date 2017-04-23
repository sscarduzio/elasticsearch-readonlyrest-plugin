package org.elasticsearch.plugin.readonlyrest.settings;

abstract class LdapSettingsDependent extends Settings {

  abstract String getName();

  abstract void setLdapSettings(LdapSettings ldapSettings);
}
