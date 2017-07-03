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
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAuthentication;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyUnixRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.codec.digest.Crypt.crypt;


public class AuthKeyUnixSyncRule extends BasicAuthentication {

    private final Logger logger;
    private final AuthKeyUnixRuleSettings settings;

    public AuthKeyUnixSyncRule(AuthKeyUnixRuleSettings s, ESContext context) {
        super(s, context);
        this.logger = context.logger(AuthKeyUnixSyncRule.class);
        this.settings = s;
    }

    @Override
    protected boolean authenticate(String configuredAuthKey, BasicAuth basicAuth) {
        try {
            String decodedProvided = new String(Base64.getDecoder().decode(basicAuth.getBase64Value()), StandardCharsets.UTF_8);
            decodedProvided = roundHash(configuredAuthKey.split(":"), decodedProvided.split(":"));
            return decodedProvided.equals(configuredAuthKey);
        } catch (Throwable e) {
            logger.warn("Exception while authentication", e);
            return false;
        }
    }

    private String roundHash(String[] key, String[] login) {
        Pattern p = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*");
        Matcher m = p.matcher(key[1]);
        String result = "";
        if (m.find()) {
            result = login[0] + ":" + crypt(login[1], m.group(1));
        }
        return result;
    }

    @Override
    public String getKey() {
        return settings.getName();
    }
}