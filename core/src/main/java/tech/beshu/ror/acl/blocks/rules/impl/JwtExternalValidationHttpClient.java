package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.ImmutableMap;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceHttpClient;
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
