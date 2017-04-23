package org.elasticsearch.plugin.readonlyrest.settings;

abstract class Settings {
  void configure() {}
  protected void validate() {}
}
