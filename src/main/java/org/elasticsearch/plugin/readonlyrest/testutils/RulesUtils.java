package org.elasticsearch.plugin.readonlyrest.testutils;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;

public class RulesUtils {

  public static Class<? extends Rule> classOfRule(Rule rule) {
    return rule instanceof AsyncRuleAdapter
        ? ((AsyncRuleAdapter) rule).getUnderlying().getClass()
        : rule.getClass();
  }

}
