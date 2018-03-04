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
package tech.beshu.ror.requestcontext;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.BlockHistory;
import tech.beshu.ror.acl.blocks.Block;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.impl.GroupsAsyncRule;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.acl.domain.Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.request.RequestContextShim;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.requestcontext.transactionals.TxKibanaIndices;
import tech.beshu.ror.utils.BasicAuthUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public abstract class RequestContext extends Delayed implements RequestContextShim, Value.VariableResolver {

  private final static String X_KIBANA_INDEX_HEADER = "x-ror-kibana_index";

  protected Transactional<Set<String>> indices;
  private TxKibanaIndices kibanaIndices;
  private Boolean doesInvolveIndices = false;
  private VariablesManager variablesManager;
  private Map<String, String> requestHeaders;
  private Transactional<Optional<LoggedUser>> loggedInUser;
  private Transactional<Map<String, String>> responseHeaders;
  private List<BlockHistory> history = Lists.newArrayList();
  private Date timestamp = new Date();
  private ESContext context;

  public RequestContext(String name, ESContext context) {
    super(name, context);
    this.context = context;
    init();
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

    this.indices = new Transactional<Set<String>>("rc-indices", context) {
      @Override
      public Set<String> initialize() {
        return involvesIndices() ? extractIndices() : Collections.emptySet();
      }

      @Override
      public Set<String> copy(Set<String> initial) {
        return Sets.newHashSet(initial);
      }

      @Override
      public void onCommit(Set<String> value) {
        writeIndices(value);
      }
    };


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

  public void setIndices(Set<String> newIndices) {
    indices.mutate(newIndices);
  }

  abstract public Set<String> getSnapshots();

  public Map<String, String> getHeaders() {
    return this.requestHeaders;
  }

  abstract public Set<String> getExpandedIndices(Set<String> i);

  abstract public Set<String> getAllIndicesAndAliases();

  abstract protected Boolean extractDoesInvolveIndices();

  abstract protected Set<String> extractIndices();

  abstract protected Boolean extractIsReadRequest();

  abstract protected Boolean extractIsCompositeRequest();

  abstract protected void writeIndices(Set<String> indices);

  abstract protected void commitResponseHeaders(Map<String, String> hmap);

  abstract public String getAction();

  abstract public String getId();

  abstract public String getContent();

  abstract public Integer getContentLength();

  abstract public HttpMethod getMethod();

  abstract public String getUri();

  abstract public String getType();

  abstract public Long getTaskId();

  abstract public String getRemoteAddress();

  abstract public String getLocalAddress();

  abstract protected Map<String, String> extractRequestHeaders();

  public ESContext getContext() {
    return context;
  }

  public List<BlockHistory> getHistory() {
    return history;
  }

  public void addToHistory(Block block, Set<RuleExitResult> results) {
    BlockHistory blockHistory = new BlockHistory(block.getName(), results);
    history.add(blockHistory);
  }

  public void setResponseHeader(String name, String value) {
    Map<String, String> copied = responseHeaders.copy(responseHeaders.get());
    copied.put(name, value);
    responseHeaders.mutate(copied);
  }

  public Optional<LoggedUser> getLoggedInUser() {
    return loggedInUser.get();
  }

  public void setLoggedInUser(LoggedUser user) {
    loggedInUser.mutate(Optional.of(user));
  }

  public boolean isDebug() {
    return true;
//    return getContext().logger(this.getClass()).isDebugEnabled();
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

    String content;
    if (getContentLength() == 0) {
      content = "<N/A>";
    }
    else if (isDebug()) {
      content = getContent();
    }
    else {
      content = "<OMITTED, LENGTH=" + getContentLength() + ">";
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

    String currentGroup;
    if (getHeaders().containsKey(GroupsAsyncRule.CURRENT_GROUP_HEADER)) {
      currentGroup = getHeaders().get(GroupsAsyncRule.CURRENT_GROUP_HEADER);
      if (Strings.isNullOrEmpty(currentGroup)) {
        currentGroup = "<empty>";
      }
    }
    else {
      currentGroup = "N/A";
    }

    return "{ ID:" + getId() +
      ", TYP:" + getType() +
      ", CGR:" + currentGroup +
      ", USR:" + (loggedInUser.isPresent()
      ? loggedInUser.get()
      : (optBasicAuth.map(basicAuth -> basicAuth.getUserName() + "(?)").orElse("[no basic auth header]"))) +
      ", BRS:" + !Strings.isNullOrEmpty(getHeaders().get("User-Agent")) +
      ", ACT:" + getAction() +
      ", OA:" + getRemoteAddress() +
      ", DA:" + getLocalAddress() +
      ", IDX:" + theIndices +
      ", MET:" + getMethod() +
      ", PTH:" + getUri() +
      ", CNT:" + content +
      ", HDR:" + theHeaders +
      ", HIS:" + hist +
      " }";
  }


  public boolean isReadRequest() {
    return extractIsReadRequest();
  }

  public boolean isComposite() {
    return extractIsCompositeRequest();
  }
}
