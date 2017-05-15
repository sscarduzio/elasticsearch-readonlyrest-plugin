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

import java.util.Calendar;
import java.util.Date;
import java.util.Optional;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.utils.OAuthUtils;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

public class TokenSyncRule extends SyncRule {
    private final Logger logger = Loggers.getLogger(getClass());
    private final Boolean needToCheck;

    public TokenSyncRule(Settings s) throws RuleNotConfiguredException {
        super();
        String tokenCheck = s.get("token", "TRUE");
        // if this param is set to false
        // we do not need to check the token
        needToCheck = Boolean.parseBoolean(tokenCheck);        
    }

    public static Optional<TokenSyncRule> fromSettings(Settings s) {
		try {
			return Optional.of(new TokenSyncRule(s));
		} catch (RuleNotConfiguredException e) {
			return Optional.empty();
		}
	}

	@Override
	public RuleExitResult match(RequestContext rc) {
		logger.debug("BEGIN Check token");
        if (!needToCheck && rc.getToken() == null)
           return MATCH;
        if (rc.getToken() == null)
            return NO_MATCH;
        boolean valid = true;
        valid &= OAuthUtils.verifyTokenIntegrity(rc.getToken(), rc.getToken().getPublicKey());
        // expiration date check
        Date expDate = rc.getToken().getExp();
        Date now = Calendar.getInstance().getTime();
        valid &= expDate.after(now);
        // Save the status of the token for later
        rc.getToken().setValid(valid);
        logger.debug("END Check token");
        return valid ? MATCH : NO_MATCH;
	}

}
