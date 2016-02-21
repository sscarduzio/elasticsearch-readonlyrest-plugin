package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

/**
 * Created by sscarduzio on 14/02/2016.
 */
public class MaxBodyLengthRule extends Rule {
  private Integer maxBodyLength;

  public MaxBodyLengthRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    maxBodyLength = s.getAsInt("maxBodyLength", null);
    if (maxBodyLength == null) {
      throw new RuleNotConfiguredException();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    return (rc.getRequest().content().length() > maxBodyLength) ? NO_MATCH : MATCH;
  }
}
