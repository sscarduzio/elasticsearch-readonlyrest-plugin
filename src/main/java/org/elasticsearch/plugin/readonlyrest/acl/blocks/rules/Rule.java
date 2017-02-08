package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.base.CaseFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;


abstract public class Rule {
    private final String KEY;
    protected RuleExitResult MATCH;
    protected RuleExitResult NO_MATCH;
    private Block.Policy policy = null;

    public Rule(Settings s) {
        KEY = mkKey(getClass());
        MATCH = new RuleExitResult(true, this);
        NO_MATCH = new RuleExitResult(false, this);
    }

    protected static String mkKey(Class<? extends Rule> c) {
        return CaseFormat.LOWER_CAMEL.to(
                CaseFormat.LOWER_UNDERSCORE,
                c.getSimpleName().replace("SyncRule", "")
        );
    }

    public String getKey() {
        return KEY;
    }

    public Block.Policy getPolicy() {
        return policy;
    }

}
