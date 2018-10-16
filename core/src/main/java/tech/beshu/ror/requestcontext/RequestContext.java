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
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.request.RequestContextShim;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.utils.BasicAuthUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public abstract class RequestContext extends Delayed implements RequestContextShim, Value.VariableResolver {

  protected Transactional<Set<String>> indices;
  private Transactional<String> kibanaIndex;
  private Boolean doesInvolveIndices = false;
  private VariablesManager variablesManager;
  private Map<String, String> requestHeaders;
  private Transactional<Optional<LoggedUser>> loggedInUser;
  private Transactional<Map<String, String>> responseHeaders;
  private Transactional<Map<String, String>> contextHeaders;
  private List<BlockHistory> history = Lists.newArrayList();
  private Date timestamp = new Date();
  private ESContext context;
  private Transactional<Set<String>> snapshots;
  private Transactional<Set<String>> repositories;

  public RequestContext(String name, ESContext context) {
    super(name, context);
    this.context = context;
    init();
  }

  public void init() {
    this.doesInvolveIndices = extractDoesInvolveIndices();

    this.kibanaIndex = new Transactional<String>("tx_kibana_index", context) {
      @Override
      public String initialize() {
        return null;
      }

      @Override
      public String copy(String initial) {
        return initial;
      }

      @Override
      public void onCommit(String value) {
        setResponseHeader(Constants.HEADER_KIBANA_INDEX, value);
      }

      @Override
      public void reset() {
        super.reset();
      }
    };

    this.requestHeaders = new TreeMap<>(extractRequestHeaders());

    String curGroup = requestHeaders.get(Constants.HEADER_GROUP_CURRENT);
    if (curGroup != null && curGroup.contains(",")) {
      throw context.rorException("Current groups contains more than one values: " + curGroup);
    }

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

    this.contextHeaders = new Transactional<Map<String, String>>("rc-ctx-headers", context) {
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
        for (String k : hMap.keySet()) {
          writeContextHeader(k, hMap.get(k));
        }
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
          if (Constants.KIBANA_METADATA_ENABLED) {
            Map<String, String> theMap = responseHeaders.get();

            theMap.put(Constants.HEADER_USER_ROR, loggedUser.getId());

            if (!loggedUser.getAvailableGroups().isEmpty()) {
              String avGroups = Joiner.on(",").join(loggedUser.getAvailableGroups());
              theMap.put(Constants.HEADER_GROUPS_AVAILABLE, avGroups);
            }

            loggedUser.getCurrentGroup().ifPresent(cg -> theMap.put(Constants.HEADER_GROUP_CURRENT, cg));
            responseHeaders.mutate(theMap);
          }
        });

      }
    };

    this.repositories = new Transactional<Set<String>>("rc-repositories", context) {
      @Override
      public Set<String> initialize() {
        return extractRepositories();
      }

      @Override
      public Set<String> copy(Set<String> initial) {
        return Sets.newHashSet(initial);
      }

      @Override
      public void onCommit(Set<String> value) {
        writeRepositories(value);
      }
    };

    this.snapshots = new Transactional<Set<String>>("rc-snapshots", context) {
      @Override
      public Set<String> initialize() {
        return extractSnapshots();
      }

      @Override
      public Set<String> copy(Set<String> initial) {
        return Sets.newHashSet(initial);
      }

      @Override
      public void onCommit(Set<String> value) {
        writeSnapshots(value);
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

    // Register for cascading effects
    this.repositories.delegateTo(this);
    this.snapshots.delegateTo(this);
    this.indices.delegateTo(this);
    this.loggedInUser.delegateTo(this);
    this.kibanaIndex.delegateTo(this);
    this.responseHeaders.delegateTo(this);
    this.contextHeaders.delegateTo(this);
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

  public Set<String> getSnapshots() {
    return snapshots.getInitial();
  }

  public void setSnapshots(Set<String> newSnapshots) {
    snapshots.mutate(newSnapshots);
  }

  public Set<String> getRepositories() {
    return repositories.getInitial();
  }

  public void setRepositories(Set<String> newRepositories) {
    repositories.mutate(newRepositories);
  }

  abstract protected Set<String> extractSnapshots();

  abstract protected void writeSnapshots(Set<String> newSnapshots);

  abstract protected Set<String> extractRepositories();

  public Map<String, String> getHeaders() {
    return this.requestHeaders;
  }

  protected abstract void writeRepositories(Set<String> newRepos);

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

  abstract protected Map<String, String> extractRequestHeaders();

  abstract protected String extractContextHeader(String key);

  abstract protected void writeContextHeader(String key, String value);

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

  public Map<String, String> getResponseHeaders() {
    return Maps.newHashMap(responseHeaders.get());
  }

  public void setResponseHeader(String name, String value) {
    Map<String, String> copied = responseHeaders.copy(responseHeaders.get());
    copied.put(name, value);
    responseHeaders.mutate(copied);
  }

  public Map<String, String> getContextHeader() {
    return Maps.newHashMap(contextHeaders.get());
  }

  public void setContextHeader(String name, String value) {
    Map<String, String> copied = contextHeaders.copy(contextHeaders.get());
    copied.put(name, value);
    contextHeaders.mutate(copied);
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
      // We need to copy the original headers map so we don't change it by sanitising it
      Map<String, String> originalHeaders = getHeaders();
      Map<String, String> hdrs = new HashMap<>(originalHeaders.size());
      hdrs.putAll(getHeaders());

      // Sanitize credentials leaks in non-debug logs
      Map<String, String> sanitized = Maps.newHashMap();
      for (Map.Entry<String, String> ks : hdrs.entrySet()) {
        String key = ks.getKey();
        if ("Authorization".equalsIgnoreCase(key)) {
          sanitized.put(key, "<OMITTED>");
        }
      }
      hdrs.putAll(sanitized);

      theHeaders = hdrs.toString();
    }
    else {
      theHeaders = getHeaders().toString();
    }

    String hist = Joiner.on(", ").join(getHistory());
    Optional<BasicAuthUtils.BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(getHeaders());

    Optional<LoggedUser> loggedInUser = getLoggedInUser();

    String currentGroup;
    if (getHeaders().containsKey(Constants.HEADER_GROUP_CURRENT)) {
      currentGroup = getHeaders().get(Constants.HEADER_GROUP_CURRENT);
      if (Strings.isNullOrEmpty(currentGroup)) {
        currentGroup = "<empty>";
      }
    }
    else {
      currentGroup = "N/A";
    }

    return new StringBuilder()
        .append("{ ID:")
        .append(getId())
        .append(", TYP:")
        .append(getType())
        .append(", CGR:")
        .append(currentGroup)
        .append(", USR:")
        .append(
            loggedInUser.isPresent() ? loggedInUser.get() : (optBasicAuth.map(basicAuth -> basicAuth.getUserName() + "(?)").orElse("[no basic auth header]")))
        .append(", BRS:")
        .append(!Strings.isNullOrEmpty(getHeaders().get("User-Agent")))
        .append(", KDX:")
        .append(kibanaIndex.get())
        .append(", ACT:")
        .append(getAction())
        .append(", OA:")
        .append(getRemoteAddress())
        .append(", DA:")
        .append(getLocalAddress())
        .append(", IDX:")
        .append(theIndices)
        .append(", MET:")
        .append(getMethod())
        .append(", PTH:")
        .append(getUri())
        .append(", CNT:")
        .append(content)
        .append(", HDR:")
        .append(theHeaders)
        .append(", HIS:")
        .append(hist)
        .append(" }")
        .toString();
  }

  public boolean isReadRequest() {
    if(Constants.REST_METADATA_PATH.equals(getUri()) && HttpMethod.GET.equals(getMethod())){
      return true;
    }
    return extractIsReadRequest();
  }

  public boolean isComposite() {
    return extractIsCompositeRequest();
  }

  public String getKibanaIndex() {
    return kibanaIndex.get();
  }

  public void setKibanaIndex(String kibanaIndex) {
    this.kibanaIndex.mutate(kibanaIndex);
  }
}
