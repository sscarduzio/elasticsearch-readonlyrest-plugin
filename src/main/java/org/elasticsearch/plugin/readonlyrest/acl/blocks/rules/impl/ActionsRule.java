package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class ActionsRule extends Rule {

  private final static ESLogger logger = Loggers.getLogger(ActionsRule.class);

  protected MatcherWithWildcards m;

  public ActionsRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    m = new MatcherWithWildcards(s, KEY);
  }


  @Override
  public RuleExitResult match(RequestContext rc) {
    if(m.match(new String[]{rc.getAction()})){
      return MATCH;
    }
    logger.debug("This request uses the action'" + rc.getAction() + "' and none of them is on the list.");
    return NO_MATCH;
  }
}
