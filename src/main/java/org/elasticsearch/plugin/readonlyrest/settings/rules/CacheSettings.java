package org.elasticsearch.plugin.readonlyrest.settings.rules;

import java.time.Duration;

public interface CacheSettings {
  Duration getCacheTtl();
}
