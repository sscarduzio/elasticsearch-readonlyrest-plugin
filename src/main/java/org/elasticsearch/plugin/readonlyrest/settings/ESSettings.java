package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

public class ESSettings extends Settings {

  @JsonProperty("readonlyrest")
  private RorSettings rorSettings;

  public RorSettings getRorSettings() {
    return rorSettings;
  }

  @Override
  public void configure() {
    rorSettings.configure();
    validate();
  }

  @Override
  protected void validate() {
    if(rorSettings == null) {
      throw new ConfigMalformedException("Cannot find 'readonlyrest' attribute");
    }
    rorSettings.validate();
  }
}
