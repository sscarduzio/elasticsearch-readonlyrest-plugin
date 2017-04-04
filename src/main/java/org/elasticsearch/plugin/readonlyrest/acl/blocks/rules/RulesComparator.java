package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authorization;

import java.util.Comparator;

public class RulesComparator implements Comparator<Rule> {

  public static RulesComparator INSTANCE = new RulesComparator();

  @Override
  public int compare(Rule rule1, Rule rule2) {
    return Integer.compare(getPriority(rule1), getPriority(rule2));
  }

  private int getPriority(Rule rule) {
    if (rule instanceof Authorization)
      return 1000;
    else if (rule instanceof Authentication)
      return 900;
    else
      // dummy solution - to be fixed in future
      return rule.getKey().length();
  }
}
