package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

import java.util.concurrent.CompletableFuture;

public abstract class AsyncRule extends Rule {

    public abstract CompletableFuture<RuleExitResult> match(RequestContext rc);

}
