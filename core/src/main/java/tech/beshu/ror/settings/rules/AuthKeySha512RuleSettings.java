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
package tech.beshu.ror.settings.rules;

public class AuthKeySha512RuleSettings extends AuthKeyRuleSettings {

  public static final String ATTRIBUTE_NAME = "auth_key_sha512";

  public AuthKeySha512RuleSettings(String authKey) {
    super(authKey.toLowerCase());
  }

  public static AuthKeySha512RuleSettings from(String authKey) {
    return new AuthKeySha512RuleSettings(authKey);
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
