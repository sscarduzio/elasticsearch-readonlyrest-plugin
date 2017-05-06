package org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettings;

public interface GroupsProviderServiceClientFactory {

  GroupsProviderServiceClient getClient(UserGroupsProviderSettings settings);
}
