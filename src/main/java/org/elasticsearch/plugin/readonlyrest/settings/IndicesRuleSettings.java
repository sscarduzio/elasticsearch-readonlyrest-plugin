package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;

import java.util.List;

public class IndicesRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices";

  @JsonProperty("indices")
  private List<String> indices = Lists.newArrayList();

}
