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
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.CheckedFunction;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public abstract class RequestContext extends Delayed implements IndicesRequestContext {

  public RequestContext(String name, ESContext context) {
    super(name, context);
  }

  abstract public Optional<LoggedUser> getLoggedInUser();

  abstract public void setLoggedInUser(LoggedUser user);

  abstract public Date getTimestamp();

  abstract public Set<String> getTransientIndices();

  abstract public String getAction();

  abstract public String getId();

  abstract public Long getTaskId();

  abstract public Map<String, String> getHeaders();

  abstract public String getRemoteAddress();

  abstract public Set<String> getIndices();

  abstract public boolean isDebug();

  abstract public boolean involvesIndices();

  abstract public Boolean hasSubRequests();

  abstract public Integer scanSubRequests(CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer);

  abstract public void setResponseHeader(String name, String value);

  abstract public String getContent();

  abstract public HttpMethod getMethod();

  abstract public String getUri();

  abstract public String getType();

  abstract public Set<BlockHistory> getHistory();

  abstract public void addToHistory(Block block, Set<RuleExitResult> results);

  abstract public String getClusterUUID();

  abstract public String getNodeUUID();

  public String asJson(Boolean debug) throws JsonProcessingException {

    return new SerializationTool().toJson(this);
  }

  public String toString() {
    return toString(false);
  }

  private String toString(boolean skipIndices) {
    String theIndices;
    if (skipIndices || !involvesIndices()) {
      theIndices = "<N/A>";
    }
    else {
      theIndices = Joiner.on(",").skipNulls().join(getTransientIndices());
    }

    String content = getContent();
    if (Strings.isNullOrEmpty(content)) {
      content = "<N/A>";
    }
    String theHeaders;
    if (!isDebug()) {
      theHeaders = Joiner.on(",").join(getHeaders().keySet());
    }
    else {
      theHeaders = getHeaders().toString();
    }

    String hist = Joiner.on(", ").join(getHistory());
    Optional<BasicAuthUtils.BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(getHeaders());

    Optional<LoggedUser> loggedInUser = getLoggedInUser();

    return "{ ID:" + getId() +
      ", TYP:" + getType() +
      ", USR:" + (loggedInUser.isPresent()
      ? loggedInUser.get()
      : (optBasicAuth.map(basicAuth -> basicAuth.getUserName() + "(?)").orElse("[no basic auth header]"))) +
      ", BRS:" + !Strings.isNullOrEmpty(getHeaders().get("User-Agent")) +
      ", ACT:" + getAction() +
      ", OA:" + getRemoteAddress() +
      ", IDX:" + theIndices +
      ", MET:" + getMethod() +
      ", PTH:" + getUri() +
      ", CNT:" + (isDebug() ? content : "<OMITTED, LENGTH=" + getContent().length() + ">") +
      ", HDR:" + theHeaders +
      ", HIS:" + hist +
      " }";
  }


}
