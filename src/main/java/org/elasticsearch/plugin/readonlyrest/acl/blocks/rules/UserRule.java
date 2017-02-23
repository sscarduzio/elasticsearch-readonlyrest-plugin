package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;

public interface UserRule {
  RuleExitResult match(RequestContext rc);
}
