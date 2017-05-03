package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.Set;
import java.util.stream.Collectors;

public class MethodsRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "methods";

  private final Set<HttpMethod> methods;

  public static MethodsRuleSettings from(Set<String> methods) {
    return new MethodsRuleSettings(methods.stream()
        .map(value -> HttpMethod.fromString(value)
            .<ConfigMalformedException>orElseThrow(() -> new ConfigMalformedException("Unknown/unsupported http method: " + value)))
        .collect(Collectors.toSet()));
  }

  private MethodsRuleSettings(Set<HttpMethod> methods) {
    this.methods = methods;
  }

  public Set<HttpMethod> getMethods() {
    return methods;
  }
}