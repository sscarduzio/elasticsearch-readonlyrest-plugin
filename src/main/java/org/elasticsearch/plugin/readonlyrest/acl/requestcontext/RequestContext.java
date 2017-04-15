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

package org.elasticsearch.plugin.readonlyrest.acl.requestcontext;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;
import org.elasticsearch.plugin.readonlyrest.utils.ReflectionUtils;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext extends Delayed implements IndicesRequestContext {

  private final Logger logger = Loggers.getLogger(getClass());
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final Map<String, String> requestHeaders;
  private final ClusterService clusterService;
  private final IndexNameExpressionResolver indexResolver;
  private final Transactional<Set<String>> indices;
  private ThreadPool threadPool;
  private final Transactional<Map<String, String>> responseHeaders =
      new Transactional<Map<String, String>>("rc-resp-headers") {
        @Override
        public Map<String, String> initialize() {
          return Collections.emptyMap();
        }

        @Override
        public Map<String, String> copy(Map<String, String> initial) {
          Map<String, String> newMap = Maps.newHashMap(initial);
          return newMap;
        }

        @Override
        public void onCommit(Map<String, String> hMap) {
          hMap.keySet().forEach(k -> threadPool.getThreadContext().addResponseHeader(k, hMap.get(k)));
        }
      };

  private String content = null;
  private Set<BlockHistory> history = Sets.newHashSet();
  private boolean doesInvolveIndices = false;
  private Transactional<Optional<LoggedUser>> loggedInUser = new Transactional<Optional<LoggedUser>>("rc-loggedin-user") {
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

  public RequestContext(RestChannel channel, RestRequest request, String action,
      ActionRequest actionRequest, ClusterService clusterService,
      IndexNameExpressionResolver indexResolver, ThreadPool threadPool) {
    super("rc");
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.threadPool = threadPool;
    this.indexResolver = indexResolver;
    this.id = UUID.randomUUID().toString().replace("-", "");
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    request.getHeaders().keySet().stream().forEach(k -> {
      if (request.getAllHeaderValues(k).isEmpty()) {
        return;
      }
      h.put(k, request.getAllHeaderValues(k).iterator().next());
    });

    this.requestHeaders = h;

    indices = RCTransactionalIndices.mkInstance(this);

    doesInvolveIndices = actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;

    // If we get to commit this transaction, put this header.
    delay(() -> loggedInUser.get().ifPresent(loggedUser -> setResponseHeader("X-RR-User", loggedUser.getId())));

    // Register transactional values to the main queue
    indices.delegateTo(this);
    responseHeaders.delegateTo(this);
    loggedInUser.delegateTo(this);
  }

  public void addToHistory(Block block, Set<RuleExitResult> results) {
    BlockHistory blockHistory = new BlockHistory(block.getName(), results);
    history.add(blockHistory);
  }

  ActionRequest getUnderlyingRequest() {
    return actionRequest;
  }

  public String getId() {
    return id;
  }


  public boolean involvesIndices() {
    return doesInvolveIndices;
  }

  public Boolean isReadRequest() {
    return RCUtils.isReadRequest(action);
  }

  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (RCUtils.isLocalHost(remoteHost)) {
      remoteHost = RCUtils.LOCALHOST;
    }
    return remoteHost;
  }

  public String getContent() {
    if (content == null) {
      try {
        content = request.content().utf8ToString();
      } catch (Exception e) {
        content = "";
      }
    }
    return content;
  }

  public Set<String> getAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  public String getMethod() {
    return request.method().name();
  }


  public Set<String> getExpandedIndices() {
    return getExpandedIndices(getIndices());
  }

  public Set<String> getExpandedIndices(Set<String> ixsSet) {
    if (doesInvolveIndices) {
//
//      String[] ixs = ixsSet.toArray(new String[ixsSet.size()]);
//      String[] concreteIdxNames = indexResolver.concreteIndexNames(
//          clusterService.state(),
//          IndicesOptions.lenientExpandOpen(), ixs
//      );
//      return Sets.newHashSet(concreteIdxNames);
      return new MatcherWithWildcards(getIndices()).filter(getAllIndicesAndAliases());

    }
    throw new ElasticsearchException("Cannot get expanded indices of a non-index request");
  }

  public Set<String> getOriginalIndices() {
    return indices.getInitial();
  }

  public Set<String> getIndices() {
    if (!doesInvolveIndices) {
      throw new RCUtils.RRContextException("cannot get indices of a request that doesn't involve indices" + this);
    }
    return indices.get();
  }

  public void setIndices(final Set<String> newIndices) {
    if (!doesInvolveIndices) {
      throw new RCUtils.RRContextException("cannot set indices of a request that doesn't involve indices: " + this);
    }

    if (newIndices.size() == 0) {
      throw new ElasticsearchException(
          "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
              " If this was intended, set '*' as indices.");
    }

    indices.mutate(newIndices);
  }

  public Boolean hasSubRequests() {
    return !SubRequestContext.extractNativeSubrequests(actionRequest).isEmpty();
  }

  public Integer scanSubRequests(final ReflectionUtils.CheckedFunction<SubRequestContext, Optional<SubRequestContext>> replacer) {

    List<? extends IndicesRequest> subRequests = SubRequestContext.extractNativeSubrequests(actionRequest);

    // Composite request
    if (!doesInvolveIndices) {
      throw new RCUtils.RRContextException("cannot replace indices of a composite request that doesn't involve indices: " + this);
    }

    Iterator<? extends IndicesRequest> it = subRequests.iterator();
    while (it.hasNext()) {
      SubRequestContext i = new SubRequestContext(this, it.next());
      final Set<String> oldIndices = Sets.newHashSet(i.getIndices());
      final Optional<SubRequestContext> newSubRCopt;

      // Empty optional = remove sub-request from the native list
      try {
        newSubRCopt = replacer.apply(i);
        if (!newSubRCopt.isPresent()) {
          it.remove();
        }
      } catch (IllegalAccessException e) {
        e.printStackTrace();
        throw new ElasticsearchSecurityException("error gathering indices to be replaced in sub-request " + i, e);
      }
      i = newSubRCopt.get();

      // We are letting this pass, so let's commit it when we commit the sub-request.
      i.delegateTo(this);
      final Set<String> newIndices = i.getIndices();
      newIndices.remove("<no-index>");
      newIndices.remove("");
      i.setIndices(newIndices);

      if (newIndices.equals(oldIndices)) {
        logger.info("id: " + id + " - Not replacing in sub-request. Indices are the same. Old:" + oldIndices + " New:" + newIndices);
        continue;
      }
      logger.info("id: " + id + " - Replacing indices in sub-request. Old:" + oldIndices + " New:" + newIndices);

      if (newIndices.size() == 0) {
        throw new RCUtils.RRContextException(
            "Attempted to set empty indices list in a sub-request this would allow full access, therefore this is forbidden." +
                " If this was intended, set '*' as indices.");
      }

    }
    return subRequests.size();
  }

  public void setResponseHeader(String name, String value) {
    Map<String, String> oldMap = responseHeaders.get();
    oldMap.put(name, value);
    responseHeaders.mutate(oldMap);
  }

  public Map<String, String> getHeaders() {
    return this.requestHeaders;
  }

  public String getUri() {
    return request.uri();
  }

  public String getAction() {
    return action;
  }

  public Optional<LoggedUser> getLoggedInUser() {
    return loggedInUser.get();
  }

  public void setLoggedInUser(LoggedUser user) {
    loggedInUser.mutate(Optional.of(user));
  }

  @Override
  public String toString() {
    return toString(false);
  }

  private String toString(boolean skipIndices) {
    String theIndices;
    if (skipIndices || !doesInvolveIndices) {
      theIndices = "<N/A>";
    }
    else {
      theIndices = Joiner.on(",").skipNulls().join(indices.get());
    }

    String content = getContent();
    if (Strings.isNullOrEmpty(content)) {
      content = "<N/A>";
    }
    String theHeaders;
    if (!logger.isDebugEnabled()) {
      theHeaders = Joiner.on(",").join(getHeaders().keySet());
    }
    else {
      theHeaders = getHeaders().toString();
    }

    String hist = Joiner.on(", ").join(history);
    Optional<BasicAuth> optBasicAuth = BasicAuthUtils.getBasicAuthFromHeaders(getHeaders());
    return "{ ID:" + id +
        ", TYP:" + actionRequest.getClass().getSimpleName() +
        ", USR:" + (loggedInUser.get().isPresent()
        ? loggedInUser.get().get()
        : (optBasicAuth.isPresent() ? optBasicAuth.get().getUserName() + "(?)" : "[no basic auth header]")) +
        ", BRS:" + !Strings.isNullOrEmpty(requestHeaders.get("User-Agent")) +
        ", ACT:" + action +
        ", OA:" + getRemoteAddress() +
        ", IDX:" + theIndices +
        ", MET:" + request.method() +
        ", PTH:" + request.path() +
        ", CNT:" + (logger.isDebugEnabled() ? content : "<OMITTED, LENGTH=" + getContent().length() + ">") +
        ", HDR:" + theHeaders +
        ", EFF:" + effectsSize() +
        ", HIS:" + hist +
        " }";
  }


}
