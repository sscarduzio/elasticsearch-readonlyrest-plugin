package org.elasticsearch.plugin.readonlyrest.acl.definitions;

import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;

public class DefinitionsFactory implements LdapClientFactory {

  @Override
  public GroupsProviderLdapClient getClient(LdapSettings settings) {
    return null;
  }
}
