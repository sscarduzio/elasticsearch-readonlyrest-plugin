package org.elasticsearch.plugin.readonlyrest.es53x;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugin.readonlyrest.configuration.ReloadableConfiguration;

import java.io.File;
import java.io.IOException;

@Singleton
public class ReloadableConfigurationImpl extends ReloadableConfiguration {

  @Inject
  public ReloadableConfigurationImpl(Environment env) throws IOException {
    super(new File(env.configFile().toFile(), "elasticsearch.yml"));
  }
}
