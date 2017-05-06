package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class IndicesRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices";

  private final Set<Value<String>> indices;

  public static IndicesRuleSettings from(Set<String> indices) {
    return new IndicesRuleSettings(
        indices.stream()
            .map(i -> Value.fromString(i, Function.identity()))
            .collect(Collectors.toSet())
    );
  }

  private IndicesRuleSettings(Set<Value<String>> indices) {
    this.indices = indices;
  }

  public Set<Value<String>> getIndices() {
    return indices;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
