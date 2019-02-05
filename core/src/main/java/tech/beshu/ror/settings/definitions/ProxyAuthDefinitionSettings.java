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

import tech.beshu.ror.commons.settings.RawSettings;

public class ProxyAuthDefinitionSettings {

  private static final String NAME = "name";
  private static final String USER_ID_HEADER = "user_id_header";

  private final String name;
  private final String userIdHeader;

  public ProxyAuthDefinitionSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);
    this.userIdHeader = settings.stringReq(USER_ID_HEADER);
  }

  public String getName() {
    return name;
  }

  public String getUserIdHeader() {
    return userIdHeader;
  }
}
