package org.elasticsearch.plugin.readonlyrest.clients;

import java.util.concurrent.CompletableFuture;

public interface ExternalAuthenticationServiceClient {
  CompletableFuture<Boolean> authenticate(String user, String password);
}
