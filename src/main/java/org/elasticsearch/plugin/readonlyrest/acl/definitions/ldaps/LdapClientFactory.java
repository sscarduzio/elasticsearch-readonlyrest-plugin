package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.AuthenticationLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;

public interface LdapClientFactory {

  GroupsProviderLdapClient getClient(GroupsProviderLdapSettings settings);

  AuthenticationLdapClient getClient(AuthenticationLdapSettings settings);
}
