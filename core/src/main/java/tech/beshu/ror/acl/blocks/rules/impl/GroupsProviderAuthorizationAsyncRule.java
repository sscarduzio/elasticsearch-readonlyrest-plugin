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
package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.acl.blocks.rules.AsyncAuthorization;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClientFactory;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class GroupsProviderAuthorizationAsyncRule extends AsyncAuthorization {

  private final GroupsProviderAuthorizationRuleSettings settings;
  private final GroupsProviderServiceClient client;

  public GroupsProviderAuthorizationAsyncRule(GroupsProviderAuthorizationRuleSettings settings,
                                              GroupsProviderServiceClientFactory factory,
                                              ESContext context) {
    super(context);
    this.settings = settings;
    this.client = factory.getClient(settings.getUserGroupsProviderSettings());
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    return client.fetchGroupsFor(user)
      .thenApply(this::checkUserGroups);
  }

  private boolean checkUserGroups(Set<String> groups) {
    Sets.SetView<String> intersection = Sets.intersection(settings.getGroups(), Sets.newHashSet(groups));
    return !intersection.isEmpty();
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}
