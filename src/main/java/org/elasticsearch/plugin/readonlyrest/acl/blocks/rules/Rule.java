package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

public abstract class Rule {
    protected final RuleExitResult MATCH;
    protected final RuleExitResult NO_MATCH;

    Rule() {
        MATCH = new RuleExitResult(true, this);
        NO_MATCH = new RuleExitResult(false, this);
    }

    public abstract String getKey();
}
