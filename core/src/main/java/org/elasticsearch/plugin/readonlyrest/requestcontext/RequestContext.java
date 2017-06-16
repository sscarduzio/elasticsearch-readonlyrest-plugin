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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.CheckedFunction;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;


public interface RequestContext extends IndicesRequestContext {
  Optional<LoggedUser> getLoggedInUser();

  void setLoggedInUser(LoggedUser user);

  String getAction();

  String getId();

  Long getTaskId();

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

  String getType();

  Set<BlockHistory> getHistory();

  void addToHistory(Block block, Set<RuleExitResult> results);

  void reset();

  void commit();

  default String asJson(Boolean debug) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    String theIndices;
    if (!involvesIndices()) {
      theIndices = "<N/A>";
    } else {
      theIndices = Joiner.on(",").skipNulls().join(getIndices());
    }

    String content = getContent();
    if (Strings.isNullOrEmpty(content)) {
      content = "<N/A>";
    }
    String theHeaders;
    if (debug) {
      theHeaders = Joiner.on(",").join(getHeaders().keySet());
    } else {
      theHeaders = getHeaders().toString();
    }

    Optional<BasicAuthUtils.BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(getHeaders());
    Optional<LoggedUser> loggedInUser = getLoggedInUser();

    return mapper.writeValueAsString(
      ImmutableMap.<String, Object>builder()
        .put("id", getId())
        .put("type", getType())
        .put("origin", getRemoteAddress())
        .put("method", getMethod())
        .put("path", getUri())
        .put("action", getAction())
        .put("indices", theIndices)
        .put("user",  (loggedInUser.isPresent()
          ? loggedInUser.get()
          : (optBasicAuth.map(basicAuth -> basicAuth.getUserName() + "(?)").orElse("[no basic auth header]"))))
        .put("content",  (debug ? content : "<OMITTED, LENGTH=" + getContent().length() + ">"))
        .put("isBrowser", !Strings.isNullOrEmpty(getHeaders().get("User-Agent")))
        .put("headers", theHeaders)
        .put("history", getHistory())
        .build()
    );
  }
}
