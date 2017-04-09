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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

// to implement in future
// in authorize method request to external authorization system should be sent
// with user identifier and serialized roles
public class ExternalRolesAuthorizationAsyncRule extends AsyncAuthorization {

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getKey() {
    throw new UnsupportedOperationException();
  }
}
