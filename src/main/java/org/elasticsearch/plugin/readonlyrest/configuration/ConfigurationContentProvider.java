package org.elasticsearch.plugin.readonlyrest.configuration;

import java.util.concurrent.CompletableFuture;

public interface ConfigurationContentProvider {
  CompletableFuture<String> getConfiguration();
}
