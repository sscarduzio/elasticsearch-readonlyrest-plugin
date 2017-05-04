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

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.plugin.readonlyrest.acl.BlockHistory;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils.BasicAuth;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
  private final Transactional<Verbosity> logLevel = new Transactional<Verbosity>("rc-verbosity") {
    @Override
    public Verbosity initialize() {
      return Verbosity.INFO;
    }

    @Override
    public Verbosity copy(Verbosity initial) {
      return initial;
    }

    @Override
    public void onCommit(Verbosity value) {
        return;
    }
  };


  private ThreadPool threadPool;
  private final Transactional<Map<String, String>> responseHeaders =
    new Transactional<Map<String, String>>("rc-resp-headers") {
      @Override
      public Map<String, String> initialize() {
        return Maps.newHashMap();
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

  private final VariablesManager variablesManager;
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
    this.id = request.hashCode() + "-" + actionRequest.hashCode();
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    request.getHeaders().keySet().stream().forEach(k -> {
      if (request.getAllHeaderValues(k).isEmpty()) {
        return;
      }
      h.put(k, request.getAllHeaderValues(k).iterator().next());
    });
    this.requestHeaders = h;

    variablesManager = new VariablesManager(h);

    doesInvolveIndices = actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;

    indices = RCTransactionalIndices.mkInstance(this);

    // If we get to commit this transaction, put this header.
    delay(() -> loggedInUser.get().ifPresent(loggedUser -> setResponseHeader("X-RR-User", loggedUser.getId())));

    // Register transactional values to the main queue
    responseHeaders.delegateTo(this);
    loggedInUser.delegateTo(this);
    indices.delegateTo(this);
    logLevel.delegateTo(this);
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

  public String applyVariables(String original){

    String res =  variablesManager.apply(original);

    // Logged in user needs to be replaced at the last moment..
    if(getLoggedInUser().isPresent()){
      res = res.replace("@user", getLoggedInUser().get().getId());
    }

    return res;
  }

  public void setVerbosity(Verbosity v){
    logLevel.mutate(v);
  }

  public Verbosity getVerbosity(){
    return logLevel.get();
  }

  public Set<String> getAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  public Set<String> getIndexMetadata(String s) {
    SortedMap<String, AliasOrIndex> lookup = clusterService.state().metaData().getAliasAndIndexLookup();
    return lookup.get(s).getIndices().stream().map( e -> e.getIndexUUID()).collect(Collectors.toSet());
  }

  public String getMethod() {
    return request.method().name();
  }


  public Set<String> getExpandedIndices() {
    return getExpandedIndices(indices.getInitial());
  }

  public Set<String> getExpandedIndices(Set<String> ixsSet) {
    if (doesInvolveIndices) {
      //     Index[] i = indexResolver.concreteIndices(clusterService.state(), IndicesOptions.lenientExpandOpen(),"a");
      //      String[] ixs = ixsSet.toArray(new String[ixsSet.size()]);
      //      String[] concreteIdxNames = indexResolver.concreteIndexNames(
      //          clusterService.state(),
      //          IndicesOptions.lenientExpandOpen(), ixs
      //      );
      //      return Sets.newHashSet(concreteIdxNames);
      return new MatcherWithWildcards(ixsSet).filter(getAllIndicesAndAliases());
    }
    throw new ElasticsearchException("Cannot get expanded indices of a non-index request");
  }

  public Set<String> getIndices() {
    if (!doesInvolveIndices) {
      throw new RCUtils.RRContextException("cannot get indices of a request that doesn't involve indices" + this);
    }
    return indices.getInitial();
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

    if(isReadRequest()){
      Set<String> expanded = getExpandedIndices(newIndices);
      if(!expanded.isEmpty()){
        indices.mutate(expanded);
      }
      else {
        throw new IndexNotFoundException(
          "rewritten indices not found: " + Joiner.on(",").join(newIndices)
          , getIndices().iterator().next());
      }
    }
    indices.mutate(newIndices);
  }

  public Boolean hasSubRequests() {
    return !SubRequestContext.extractNativeSubrequests(actionRequest).isEmpty();
  }

  public Integer scanSubRequests(
    final ReflecUtils.CheckedFunction<SubRequestContext, Optional<SubRequestContext>> replacer, Logger logger) {

    List<? extends IndicesRequest> subRequests = SubRequestContext.extractNativeSubrequests(actionRequest);

    logger.info("found " + subRequests.size() + " subrequests");

    // Composite request #TODO should we really prevent this?
    if (!doesInvolveIndices) {
      throw new RCUtils.RRContextException("cannot replace indices of a composite request that doesn't involve indices: " + this);
    }

    Iterator<? extends IndicesRequest> it = subRequests.iterator();
    while (it.hasNext()) {
      SubRequestContext i = new SubRequestContext(this, it.next());
      final Optional<SubRequestContext> mutatedSubReqO;

      // Empty optional = remove sub-request from the native list
      try {
        mutatedSubReqO = replacer.apply(i);
        if (!mutatedSubReqO.isPresent()) {
          it.remove();
          continue;
        }
      } catch (IllegalAccessException e) {
        e.printStackTrace();
        throw new ElasticsearchSecurityException("error gathering indices to be replaced in sub-request " + i, e);
      }
      i = mutatedSubReqO.get();

      // We are letting this pass, so let's commit it when we commit the sub-request.
      i.delegateTo(this);

      if (!i.getIndices().equals(i.getIndices())) {
        i.setIndices(i.getIndices());
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
      ", HIS:" + hist +
      " }";
  }


}
