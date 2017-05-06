package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;

public interface LdapClientFactory {

  GroupsProviderLdapClient getClient(LdapSettings settings);
}
