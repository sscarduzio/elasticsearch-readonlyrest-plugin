package org.elasticsearch.plugin.readonlyrest.clients;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface GroupsProviderServiceClient {

  CompletableFuture<Set<String>> fetchGroupsFor(LoggedUser user);
}
