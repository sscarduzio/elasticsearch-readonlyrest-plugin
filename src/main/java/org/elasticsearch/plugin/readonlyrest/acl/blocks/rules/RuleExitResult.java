package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class RuleExitResult {
  private Rule condition;
  private Boolean match;

  public RuleExitResult(Boolean match, Rule condition) {
    this.match = match;
    this.condition = condition;
  }

  public Boolean isMatch() {
    return match;
  }

  public Rule getCondition() {
    return condition;
  }

  @Override
  public String toString() {
    String condString;
    if(condition != null){
     condString = condition.KEY;
    } else {
      condString = "none";
    }
    return "{ matched: " + match + ", condition: " + condString + " }";
  }
}
