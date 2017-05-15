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
package org.elasticsearch.plugin.readonlyrest.requestcontext;

import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.CheckedFunction;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface RequestContext extends IndicesRequestContext {
  Optional<LoggedUser> getLoggedInUser();
  void setLoggedInUser(LoggedUser user);
  String getAction();
  String getId();
  Map<String, String> getHeaders();
  String getRemoteAddress();
  Set<String> getIndices();
  boolean involvesIndices();
  Boolean hasSubRequests();
  Integer scanSubRequests(CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer);
  void setResponseHeader(String name, String value);
  String getContent();
  HttpMethod getMethod();
  String getUri();
  void addToHistory(Block block, Set<RuleExitResult> results);
  void reset();
  void commit();
}
