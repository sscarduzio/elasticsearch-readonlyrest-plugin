package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public abstract class GeneralAuthKeyAsyncRule extends AsyncRule {
    private static final Logger logger = Loggers.getLogger(GeneralAuthKeyAsyncRule.class);

    GeneralAuthKeyAsyncRule(Settings s) throws RuleNotConfiguredException {
        super(s);
    }

    protected abstract CompletableFuture<Boolean> authenticate(String user, String password);

    @Override
    public CompletableFuture<RuleExitResult> match(RequestContext rc) {
        String authHeader = BasicAuthUtils.extractAuthFromHeader(rc.getHeaders().get("Authorization"));

        if (authHeader != null && logger.isDebugEnabled()) {
            try {
                logger.info("Login as: " + BasicAuthUtils.getBasicAuthUser(rc.getHeaders()) + " rc: " + rc);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        if (authHeader == null) {
            return CompletableFuture.completedFuture(NO_MATCH);
        }

        String val = authHeader.trim();
        if (val.length() == 0) {
            return CompletableFuture.completedFuture(NO_MATCH);
        }

        String decodedProvided = new String(Base64.getDecoder().decode(authHeader), StandardCharsets.UTF_8);
        String[] authData = decodedProvided.split(":");
        if(authData.length != 2) {
            return CompletableFuture.completedFuture(NO_MATCH);
        }

        return authenticate(authData[0], authData[1])
                .thenApply(result -> result != null && result ? MATCH : NO_MATCH);
    }

}
