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

package tech.beshu.ror.unit.acl.blocks.rules.impl;

import com.google.common.collect.ImmutableMap;
import tech.beshu.ror.unit.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceHttpClient;
import tech.beshu.ror.httpclient.HttpClient;
import tech.beshu.ror.httpclient.RRHttpRequest;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class JwtExternalValidationHttpClient extends ExternalAuthenticationServiceHttpClient {
  public JwtExternalValidationHttpClient(HttpClient client, URI endpoint, int successStatusCode) {
    super(client, endpoint, successStatusCode);
  }

  @Override
  public CompletableFuture<Boolean> authenticate(String user, String token) {
    return client.send(RRHttpRequest.get(
        endpoint, ImmutableMap.of("Authorization", "Bearer " + token)
    )).thenApply(response -> response.getStatusCode() == successStatusCode);
  }
}
