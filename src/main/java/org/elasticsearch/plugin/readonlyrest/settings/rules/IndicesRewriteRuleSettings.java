package org.elasticsearch.plugin.readonlyrest.settings.rules;

import com.google.common.base.Strings;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class IndicesRewriteRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "indices_rewrite";

  private final Set<Pattern> targetPatterns;
  private final Value<String> replacement;

  public static IndicesRewriteRuleSettings from(List<String> list) {
    if (list.size() < 2) throw new ConfigMalformedException("Minimum two arguments required for " + ATTRIBUTE_NAME +
        ". I.e. [target1, target2, replacement]");
    return new IndicesRewriteRuleSettings(
        list.subList(0, list.size() - 1).stream()
            .distinct()
            .filter(v -> !Strings.isNullOrEmpty(v))
            .map(Pattern::compile)
            .collect(Collectors.toSet()),
        list.get(list.size() - 1)
    );
  }

  private IndicesRewriteRuleSettings(Set<Pattern> targetPatterns, String replacement) {
    this.targetPatterns = targetPatterns;
    this.replacement = Value.fromString(replacement, Function.identity());
  }

  public Set<Pattern> getTargetPatterns() {
    return targetPatterns;
  }

  public Value<String> getReplacement() {
    return replacement;
  }
}
