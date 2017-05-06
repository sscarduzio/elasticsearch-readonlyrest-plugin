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

package org.elasticsearch.plugin.readonlyrest.wiring.requestcontext;

import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;

import java.util.Optional;
import java.util.Set;

/**
 * Created by sscarduzio on 14/04/2017.
 */
public interface IndicesRequestContext {
  String getId();
  Set<String> getAllIndicesAndAliases();

  Set<String> getExpandedIndices();

  Set<String> getIndices();

  void setIndices(Set<String> newIndices);

  Optional<LoggedUser> getLoggedInUser();

  void setLoggedInUser(LoggedUser user);

  Optional<String> applyVariables(String original);

  Boolean isReadRequest();
}
