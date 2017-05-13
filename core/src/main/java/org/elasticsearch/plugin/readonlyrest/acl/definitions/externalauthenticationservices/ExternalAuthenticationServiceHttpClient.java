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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClient;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpRequest;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class ExternalAuthenticationServiceHttpClient implements ExternalAuthenticationServiceClient {

  private final URI endpoint;
  private final int successStatusCode;
  private final HttpClient client;

  public ExternalAuthenticationServiceHttpClient(HttpClient client, URI endpoint, int successStatusCode) {
    this.client = client;
    this.endpoint = endpoint;
    this.successStatusCode = successStatusCode;
  }

  @Override
  public CompletableFuture<Boolean> authenticate(String user, String password) {
    return client.send(HttpRequest.get(
        endpoint, ImmutableMap.of("Authorization", BasicAuthUtils.basicAuthHeaderValue(user, password))
    )).thenApply(response -> response.getStatusCode() == successStatusCode);
  }
}
