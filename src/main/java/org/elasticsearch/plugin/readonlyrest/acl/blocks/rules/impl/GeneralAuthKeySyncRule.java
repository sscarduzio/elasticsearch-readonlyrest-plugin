/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRule;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;

public abstract class GeneralAuthKeySyncRule extends SyncRule implements UserRule{
    private static final Logger logger = Loggers.getLogger(GeneralAuthKeySyncRule.class);

    private final String authKey;

    GeneralAuthKeySyncRule(Settings s) throws RuleNotConfiguredException {
        super();
        authKey = getAuthKey(s);
    }

    protected abstract boolean authenticate(String configured, String providedBase64);

    @Override
    public RuleExitResult match(RequestContext rc) {
        String authHeader = BasicAuthUtils.extractAuthFromHeader(rc.getHeaders().get("Authorization"));

        if (authHeader != null && logger.isDebugEnabled()) {
            try {
                logger.info("Login as: " + BasicAuthUtils.getBasicAuthUser(rc.getHeaders()) + " rc: " + rc);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        if (authKey == null || authHeader == null) {
            return NO_MATCH;
        }

        String val = authHeader.trim();
        if (val.length() == 0) {
            return NO_MATCH;
        }

        return authenticate(authKey, authHeader) ? MATCH : NO_MATCH;
    }

    private String getAuthKey(Settings s) throws RuleNotConfiguredException {
        String pAuthKey = s.get(this.getKey());
        if (pAuthKey != null && pAuthKey.trim().length() > 0) {
            return pAuthKey;
        }
        else {
            throw new RuleNotConfiguredException();
        }
    }

}
