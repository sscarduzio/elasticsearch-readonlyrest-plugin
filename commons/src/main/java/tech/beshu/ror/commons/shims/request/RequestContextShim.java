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
package tech.beshu.ror.commons.shims.request;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RequestContextShim {

  String getId();

  Set<String> getIndices();

  Date getTimestamp();

  String getAction();

  Map<String, String> getHeaders();

  String getUri();

  String getHistoryString();

  Integer getContentLength();

  String getRemoteAddress();

  String getLocalAddress();

  String getType();

  Long getTaskId();

  String getMethodString();

  Optional<String> getLoggedInUserName();

  boolean involvesIndices();

}
