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
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.requestcontext.transactionals.TxKibanaIndices;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils.CheckedFunction;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public abstract class RequestContext extends Delayed implements IndicesRequestContext {

  final static String X_KIBANA_INDEX_HEADER = "x-ror-kibana_index";
  protected Transactional<Set<String>> indices;
  private TxKibanaIndices kibanaIndices;
  private Boolean doesInvolveIndices = false;
  private VariablesManager variablesManager;
  private Map<String, String> requestHeaders;
  private Transactional<Optional<LoggedUser>> loggedInUser;
  private Transactional<Map<String, String>> responseHeaders;
  private Set<BlockHistory> history = Sets.newHashSet();
  private Date timestamp = new Date();
  private ESContext context;

  public RequestContext(String name, ESContext context) {
    super(name, context);
    this.context = context;
  }

  public void init() {
    this.doesInvolveIndices = extractDoesInvolveIndices();

    this.kibanaIndices = new TxKibanaIndices(context, s -> setResponseHeader(X_KIBANA_INDEX_HEADER, Joiner.on(",").join(s)));

    this.requestHeaders = extractRequestHeaders();
    variablesManager = new VariablesManager(this.requestHeaders, this, context);

    this.responseHeaders = new Transactional<Map<String, String>>("rc-resp-headers", context) {
      @Override
      public Map<String, String> initialize() {
        return Maps.newHashMap();
      }

      @Override
      public Map<String, String> copy(Map<String, String> initial) {
        return Maps.newHashMap(initial);
      }

      @Override
      public void onCommit(Map<String, String> hMap) {
        commitResponseHeaders(hMap);
      }
    };

    this.loggedInUser = new Transactional<Optional<LoggedUser>>("rc-loggedin-user", context) {
      @Override
      public Optional<LoggedUser> initialize() {
        return Optional.empty();
      }

      @Override
      public Optional<LoggedUser> copy(Optional<LoggedUser> initial) {
        return initial.map((u) -> new LoggedUser(u.getId()));
      }

      @Override
      public void onCommit(Optional<LoggedUser> value) {
        value.ifPresent(loggedUser -> {
          Map<String, String> theMap = responseHeaders.get();
          theMap.put("X-RR-User", loggedUser.getId());
          responseHeaders.mutate(theMap);
        });

      }
    };

    this.indices = extractTransactionalIndices();


    // If we get to commit this transaction, put this header.
    delay(() -> loggedInUser.get().ifPresent(loggedUser -> setResponseHeader("X-RR-User", loggedUser.getId())));


    // Register for cascading effects
    this.indices.delegateTo(this);
    this.loggedInUser.delegateTo(this);
    this.kibanaIndices.delegateTo(this);
    this.responseHeaders.delegateTo(this);
  }


  public Set<String> getExpandedIndices() {
    return getExpandedIndices(indices.getInitial());
  }

  public Optional<String> resolveVariable(String original) {
    return variablesManager.apply(original);
  }

  public Set<String> getIndices() {
    if (!doesInvolveIndices) {
      throw getContext().rorException("cannot get indices of a request that doesn't involve indices" + this);
    }
    return indices.getInitial();
  }

  public Map<String, String> getHeaders() {
    return this.requestHeaders;
  }

  abstract public Set<String> getExpandedIndices(Set<String> i);

  abstract public Set<String> getAllIndicesAndAliases();

  abstract protected Boolean extractDoesInvolveIndices();

  abstract protected Transactional<Set<String>> extractTransactionalIndices();

  abstract public Boolean hasSubRequests();

  abstract public Integer scanSubRequests(CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer);

  abstract protected void commitResponseHeaders(Map<String, String> hmap);

  abstract public String getAction();

  abstract public String getId();

  abstract public String getContent();

  abstract public HttpMethod getMethod();

  abstract public String getUri();

  abstract public String getType();

  abstract public String getClusterUUID();

  abstract public String getNodeUUID();

  abstract public Long getTaskId();

  abstract public String getRemoteAddress();

  abstract protected Map<String, String> extractRequestHeaders();

  public ESContext getContext() {
    return context;
  }

  public Set<BlockHistory> getHistory() {
    return history;
  }

  public void addToHistory(Block block, Set<RuleExitResult> results) {
    BlockHistory blockHistory = new BlockHistory(block.getName(), results);
    history.add(blockHistory);
  }

  public void setResponseHeader(String name, String value) {
    responseHeaders.get().put(name, value);
  }

  public Optional<LoggedUser> getLoggedInUser() {
    return loggedInUser.get();
  }

  public void setLoggedInUser(LoggedUser user) {
    loggedInUser.mutate(Optional.of(user));
  }

  public boolean isDebug() {
    return getContext().logger(this.getClass()).isDebugEnabled();
  }


  public Date getTimestamp() {
    return timestamp;
  }


  public Set<String> getTransientIndices() {
    return indices.get();
  }

  public boolean involvesIndices() {
    return doesInvolveIndices;
  }


  public Set<String> getKibanaIndices() {
    return kibanaIndices.get();
  }


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
