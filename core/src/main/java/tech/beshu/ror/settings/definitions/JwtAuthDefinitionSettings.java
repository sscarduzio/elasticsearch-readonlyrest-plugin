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

package tech.beshu.ror.settings.definitions;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import com.google.common.base.Strings;

import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.rules.NamedSettings;

/**
 * @author Datasweet <contact@datasweet.fr>
 */
public class JwtAuthDefinitionSettings implements NamedSettings {
    private static final String NAME = "name";
    private static final String SIGNATURE_ALGO = "signature_algo";
    private static final String SIGNATURE_KEY = "signature_key";
    private static final String USER_CLAIM = "user_claim";
    private static final String ROLES_CLAIM = "roles_claim";
    private static final String HEADER_NAME = "header_name";
    private static final String DEFAULT_HEADER_NAME = "Authorization";

    private final String name;
    private final byte[] key;
    private final Optional<String> userClaim;
    private final Optional<String> rolesClaim;
    private final Optional<String> algo;
    private final String headerName;

    @SuppressWarnings("unchecked")
    public JwtAuthDefinitionSettings(RawSettings settings) {
        this.name = settings.stringReq(NAME);

        String key = evalPrefixedSignatureKey(ensureString(settings, SIGNATURE_KEY));
        if (Strings.isNullOrEmpty(key))
            throw new SettingsMalformedException(
                    "Attribute '" + SIGNATURE_KEY + "' shall not evaluate to an empty string");

        this.key = key.getBytes();
        this.algo = settings.stringOpt(SIGNATURE_ALGO);
        this.userClaim = settings.stringOpt(USER_CLAIM);
        this.rolesClaim = settings.stringOpt(ROLES_CLAIM);
        this.headerName = settings.stringOpt(HEADER_NAME).orElse(DEFAULT_HEADER_NAME);
    }

    private static String ensureString(RawSettings settings, String key) {
        Object value = settings.req(key);
        if (value instanceof String)
            return (String) value;
        else
            throw new SettingsMalformedException(
                    "Attribute '" + key + "' must be a string; if it looks like a number try adding quotation marks");
    }

    private static String evalPrefixedSignatureKey(String s) {
        if (s.startsWith("text:"))
            return s.substring(5);
        else if (s.startsWith("env:"))
            return System.getenv(s.substring(4));
        else
            return s;
    }

    @Override
    public String getName() {
        return name;
    }

    public byte[] getKey() {
        return key;
    }

    public Optional<String> getAlgo() {
        return algo;
    }

    public Optional<String> getUserClaim() {
        return userClaim;
    }

    public Optional<String> getRolesClaim() {
        return rolesClaim;
    }

    public String getHeaderName() {
        return headerName;
    }
}