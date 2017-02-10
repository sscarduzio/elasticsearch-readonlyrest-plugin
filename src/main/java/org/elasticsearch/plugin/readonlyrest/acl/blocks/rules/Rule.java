package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.base.CaseFormat;
import org.elasticsearch.common.settings.Settings;


abstract public class Rule {
    protected final RuleExitResult MATCH;
    protected final RuleExitResult NO_MATCH;

    private final String KEY;
    private final String RuleClassSuffixStr;

    Rule(String suffix, Settings s) {
        RuleClassSuffixStr = suffix;
        KEY = mkKey(getClass());
        MATCH = new RuleExitResult(true, this);
        NO_MATCH = new RuleExitResult(false, this);
    }

    protected String mkKey(Class<? extends Rule> c) {
        return CaseFormat.LOWER_CAMEL.to(
                CaseFormat.LOWER_UNDERSCORE,
                c.getSimpleName().replace(RuleClassSuffixStr, "")
        );
    }

    public String getKey() {
        return KEY;
    }

}
