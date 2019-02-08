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

import com.google.common.base.Strings;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.HttpConnectionSettings;
import tech.beshu.ror.settings.rules.NamedSettings;

import java.time.Duration;
import java.util.Optional;

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
  private static final String EXTERNAL_VALIDATOR = "external_validator.url";
  private static final String EXTERNAL_VALIDATOR_VALIDATE = "external_validator.validate";
  private static final String EXTERNAL_VALIDATOR_SUCCESS_STATUS_CODE = "external_validator.success_status_code";
  private static final String EXTERNAL_VALIDATOR_CACHE_TTL = "external_validator.cache_ttl_in_sec";
  private static final String EXTERNAL_VALIDATOR_HTTP_CONNECTION_SETTINGS = "external_validator.http_connection_settings";
  private static final String DEFAULT_HEADER_NAME = "Authorization";

  private final String name;
  private final byte[] key;
  private final Optional<String> userClaim;
  private final Optional<String> rolesClaim;
  private final Optional<String> algo;
  private final Optional<String> externalValidator;
  private final String headerName;
  private final int externalValidatorCacheTtlSec;
  private final int externalValidatorSuccessStatusCode;
  private HttpConnectionSettings externalValidatorHttpConnectionSettings;

  public JwtAuthDefinitionSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);

    String key = ensureString(settings, SIGNATURE_KEY);
    if (Strings.isNullOrEmpty(key)) {
      if (
          !settings.stringOpt(SIGNATURE_ALGO).map(String::toUpperCase).orElse("NONE").equals("NONE") &&
              !settings.stringOpt(EXTERNAL_VALIDATOR).isPresent()) {
        throw new SettingsMalformedException(
            "Attribute '" + SIGNATURE_KEY + "' shall not evaluate to an empty string unless '" + EXTERNAL_VALIDATOR + "' is  defined.");
      }
      this.key = null;
    }
    else {
      String evaluated = evalPrefixedSignatureKey(key);
      if (Strings.isNullOrEmpty(evaluated)) {
        throw new SettingsMalformedException("could not find a value fo the configured signature key: " + key);
      }
      this.key = evaluated.getBytes();
    }
    this.algo = settings.stringOpt(SIGNATURE_ALGO);
    this.userClaim = settings.stringOpt(USER_CLAIM);
    this.rolesClaim = settings.stringOpt(ROLES_CLAIM);
    this.externalValidator = settings.stringOpt(EXTERNAL_VALIDATOR);
    this.headerName = settings.stringOpt(HEADER_NAME).orElse(DEFAULT_HEADER_NAME);
    this.externalValidatorSuccessStatusCode = settings.intOpt(EXTERNAL_VALIDATOR_SUCCESS_STATUS_CODE).orElse(200);
    this.externalValidatorCacheTtlSec = settings.intOpt(EXTERNAL_VALIDATOR_CACHE_TTL).orElse(60);

    boolean externalValidatorValidate = settings.booleanOpt(EXTERNAL_VALIDATOR_VALIDATE).orElse(true);
    this.externalValidatorHttpConnectionSettings = new HttpConnectionSettings(settings.innerOpt(EXTERNAL_VALIDATOR_HTTP_CONNECTION_SETTINGS).orElse(RawSettings.empty()), externalValidatorValidate);
  }

  private static String ensureString(RawSettings settings, String key) {
    Optional<Object> oValue = settings.opt(key);
    if (!oValue.isPresent()) {
      return null;
    }
    Object value = oValue.get();
    if (value instanceof String) {
      return (String) value;
    }
    else {
      throw new SettingsMalformedException(
          "Attribute '" + key + "' must be a string; if it looks like a number try adding quotation marks");
    }
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

  public Optional<String> getExternalValidator() {
    return externalValidator;
  }

  public Duration getExternalValidatorCacheTtl() {
    return Duration.ofSeconds(externalValidatorCacheTtlSec);
  }

  public String getHeaderName() {
    return headerName;
  }

  public int getExternalValidatorSuccessStatusCode() {
    return externalValidatorSuccessStatusCode;
  }

  public HttpConnectionSettings getExternalValidatorHttpConnectionSettings() {
    return externalValidatorHttpConnectionSettings;
  }
}
