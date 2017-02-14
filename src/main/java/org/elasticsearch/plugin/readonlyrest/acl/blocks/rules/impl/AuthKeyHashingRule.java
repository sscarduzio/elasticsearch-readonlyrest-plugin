package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.hash.HashFunction;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public abstract class AuthKeyHashingRule extends GeneralAuthKeySyncRule {
    private static final Logger logger = Loggers.getLogger(AuthKeySha1SyncRule.class);

    public AuthKeyHashingRule(Settings s) throws RuleNotConfiguredException {
        super(s);
    }

    @Override
    protected boolean authenticate(String configured, String providedBase64) {
        try {
            String decodedProvided = new String(Base64.getDecoder().decode(providedBase64), StandardCharsets.UTF_8);
            String shaProvided = getHashFunction().hashString(decodedProvided, Charset.defaultCharset()).toString();
            return configured.equals(shaProvided);
        } catch (Throwable e) {
            logger.warn("Exception while authentication", e);
            return false;
        }
    }

    protected abstract HashFunction getHashFunction();
}
