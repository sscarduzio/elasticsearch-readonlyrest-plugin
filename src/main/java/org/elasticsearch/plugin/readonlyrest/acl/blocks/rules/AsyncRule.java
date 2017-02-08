package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.util.concurrent.ListenableFuture;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

public abstract class AsyncRule extends Rule {

    public AsyncRule(Settings s) {
        super(s);
    }

    public abstract ListenableFuture<RuleExitResult> match(RequestContext rc);

}
