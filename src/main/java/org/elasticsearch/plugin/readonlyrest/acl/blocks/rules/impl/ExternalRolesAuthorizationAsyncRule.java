package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

// to implement in future
// in authorize method request to external authorization system should be sent
// with user identifier and serialized roles
public class ExternalRolesAuthorizationAsyncRule extends AsyncAuthorization {

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles) {
    throw new NotImplementedException();
  }

  @Override
  protected Set<String> getRoles() {
    throw new NotImplementedException();
  }

  @Override
  public String getKey() {
    throw new NotImplementedException();
  }
}
