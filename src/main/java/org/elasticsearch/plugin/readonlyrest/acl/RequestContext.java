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

package org.elasticsearch.plugin.readonlyrest.acl;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.ReflectionUtils;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  /*
    * A regular expression to match the various representations of "localhost"
    */
  private static final Pattern localhostRe = Pattern.compile("^(127(\\.\\d+){1,3}|[0:]+1)$");
  private static final String LOCALHOST = "127.0.0.1";
  private static MatcherWithWildcards readRequestMatcher = new MatcherWithWildcards(Sets.newHashSet(
    "cluster:monitor/*",
    "cluster:*get*",
    "cluster:*search*",
    "indices:admin/aliases/exsists",
    "indices:admin/aliases/get",
    "indices:admin/exists*",
    "indices:admin/get*",
    "indices:admin/mappings/fields/get*",
    "indices:admin/mappings/get*",
    "indices:admin/refresh*",
    "indices:admin/types/exists",
    "indices:admin/validate/*",
    "indices:data/read/*"
  ));

  private final Logger logger = Loggers.getLogger(getClass());
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final Map<String, String> headers;
  private final ThreadPool threadPool;
  private final ClusterService clusterService;
  private Set<String> indices = null;
  private String content = null;
  private RequestSideEffects sideEffects;
  private Set<BlockHistory> history = Sets.newHashSet();
  private Set<String> originalIndices;
  private String loggedInUser;

  public RequestContext(RestChannel channel, RestRequest request, String action,
                        ActionRequest actionRequest, ClusterService clusterService, ThreadPool threadPool) {
    this.sideEffects = new RequestSideEffects();
    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.threadPool = threadPool;
    this.id = UUID.randomUUID().toString().replace("-", "");
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    request.getHeaders().keySet().stream().forEach(k -> {
      if (request.getAllHeaderValues(k).isEmpty()) {
        return;
      }
      h.put(k, request.getAllHeaderValues(k).iterator().next());
    });

    this.headers = h;

  }

  public void addToHistory(Block block, Set<RuleExitResult> results) {
    BlockHistory blockHistory = new BlockHistory(block.getName(), results);
    history.add(blockHistory);
  }

  public String getId() {
    return id;
  }

  public void commit() {
    sideEffects.commit();
    if (!Strings.isNullOrEmpty(getLoggedInUser())) {
      doSetResponseHeader("X-RR-User", getLoggedInUser());
    }
  }

  public void reset() {
    loggedInUser = null;
    indices = null;
    sideEffects.clear();
  }

  public boolean involvesIndices() {
    return actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;
  }

  public boolean isReadRequest() {
    return readRequestMatcher.match(action);
  }

  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (localhostRe.matcher(remoteHost).find()) {
      remoteHost = LOCALHOST;
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

  public Set<String> getAvailableIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  public String getMethod() {
    return request.method().name();
  }

  public Set<String> getExpandedIndices() {
    return new MatcherWithWildcards(getIndices()).filter(getAvailableIndicesAndAliases());
  }

  public Set<String> getOriginalIndices() {
    if (originalIndices == null) {
      originalIndices = getCurrentIndices();
    }
    return originalIndices;
  }

  public Set<String> getIndices() {
    if (!involvesIndices()) {
      throw new RRContextException("cannot get indices of a request that doesn't involve indices" + this);
    }
    if (indices == null) {
      indices = getCurrentIndices();
      originalIndices = indices;
    }
    return indices;
  }

  public void setIndices(final Set<String> newIndices) {
    if (!involvesIndices()) {
      throw new RRContextException("cannot set indices of a request that doesn't involve indices: " + this);
    }

    if (newIndices.size() == 0) {
      throw new ElasticsearchException(
        "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
          " If this was intended, set '*' as indices.");
    }

    indices = newIndices;
    sideEffects.appendEffect(() -> doSetIndices(newIndices));
  }

  public Set<String> getCurrentIndices() {
    if (!involvesIndices()) {
      throw new RRContextException("cannot get indices of a request that doesn't involve indices");
    }

    logger.debug("Finding indices for: " + toString(true));

    String[] indices = new String[0];
    ActionRequest ar = actionRequest;
    // CompositeIndicesRequests
    if (ar instanceof MultiGetRequest) {
      MultiGetRequest cir = (MultiGetRequest) ar;

      for (MultiGetRequest.Item ir : cir.getItems()) {
        indices = ArrayUtils.concat(indices, ir.indices(), String.class);
      }
    }
    else if (ar instanceof MultiSearchRequest) {
      MultiSearchRequest cir = (MultiSearchRequest) ar;

      for (SearchRequest ir : cir.requests()) {
        indices = ArrayUtils.concat(indices, ir.indices(), String.class);
      }
    }
    else if (ar instanceof MultiTermVectorsRequest) {
      MultiTermVectorsRequest cir = (MultiTermVectorsRequest) ar;

      for (TermVectorsRequest ir : cir.getRequests()) {
        indices = ArrayUtils.concat(indices, ir.indices(), String.class);
      }
    }
    else if (ar instanceof BulkRequest) {
      BulkRequest cir = (BulkRequest) ar;

      for (DocWriteRequest<?> ir : cir.requests()) {
        String[] docIndices = ReflectionUtils.extractStringArrayFromPrivateMethod("indices", ir, logger);
        if (docIndices.length == 0) {
          docIndices = ReflectionUtils.extractStringArrayFromPrivateMethod("index", ir, logger);
        }
        indices = ArrayUtils.concat(indices, docIndices, String.class);
      }
    }
    else if (ar instanceof CompositeIndicesRequest) {
      logger.error("Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately!");
    }
    else {
      indices = ReflectionUtils.extractStringArrayFromPrivateMethod("indices", ar, logger);
      if (indices.length == 0) {
        indices = ReflectionUtils.extractStringArrayFromPrivateMethod("index", ar, logger);
      }
    }

    if (indices == null) {
      indices = new String[0];
    }

    Set<String> indicesSet = org.elasticsearch.common.util.set.Sets.newHashSet(indices);

    if (logger.isDebugEnabled()) {
      String idxs = String.join(",", indicesSet);
      logger.debug("Discovered indices: " + idxs);
    }

    return indicesSet;
  }

  public void doSetIndices(final Set<String> newIndices) {
    newIndices.remove("<no-index>");
    newIndices.remove("");

    if (newIndices.equals(getCurrentIndices())) {
      logger.info("id: " + id + " - Not replacing. Indices are the same. Old:" + getCurrentIndices() + " New:" + newIndices);
      return;
    }
    logger.info("id: " + id + " - Replacing indices. Old:" + getCurrentIndices() + " New:" + newIndices);

    if (newIndices.size() == 0) {
      throw new ElasticsearchException(
        "Attempted to set empty indices list, this would allow full access, therefore this is forbidden." +
          " If this was intended, set '*' as indices.");
    }

    Class<?> c = actionRequest.getClass();
    final List<Throwable> errors = Lists.newArrayList();

    errors.addAll(ReflectionUtils.fieldChanger(c, "indices", logger, this,
                                               (Field f) -> {
                                                 String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
                                                 f.set(actionRequest, idxArray);
                                                 return null;
                                               }
    ));


    // Take care of writes
    if (!errors.isEmpty() && newIndices.size() == 1) {
      errors.clear();
      errors.addAll(ReflectionUtils.fieldChanger(c, "index", logger, this, (f) -> {
        f.set(actionRequest, newIndices.iterator().next());
        return null;
      }));
    }

    if (!errors.isEmpty() && actionRequest instanceof IndicesAliasesRequest) {
      IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
      List<IndicesAliasesRequest.AliasActions> actions = iar.getAliasActions();
      actions.forEach(a -> {
        errors.addAll(ReflectionUtils.fieldChanger(a.getClass(), "indices", logger, this, (f) -> {
          String[] idxArray = newIndices.toArray(new String[newIndices.size()]);
          f.set(a, idxArray);
          return null;
        }));
      });
    }

    if (errors.isEmpty()) {
      indices.clear();
      indices.addAll(newIndices);
      logger.debug("success changing indices: " + newIndices + " correctly set as " + getCurrentIndices());
    }
    else {
      errors.forEach(e -> {
        logger.error("Failed to set indices " + e.toString());
      });
    }

  }

  public void setResponseHeader(String name, String value) {
    sideEffects.appendEffect(() -> doSetResponseHeader(name, value));
  }

  public void doSetResponseHeader(String name, String value) {
    threadPool.getThreadContext().addResponseHeader(name, value);
  }

  public Map<String, String> getHeaders() {
    return this.headers;
  }

  public String getUri() {
    return request.uri();
  }

  public String getAction() {
    return action;
  }

  public String getLoggedInUser() {
    return loggedInUser;
//    String user = BasicAuthUtils.getBasicAuthUser(getHeaders());
//    if (user == null)
//      user = ProxyAuthSyncRule.getUser(getHeaders());
//    return user;
  }

  public void setLoggedInUser(String user) {
    loggedInUser = user;
  }

  @Override
  public String toString() {
    return toString(false);
  }

  private String toString(boolean skipIndices) {
    String theIndices;
    if (skipIndices || !involvesIndices()) {
      theIndices = "<N/A>";
    }
    else {
      theIndices = Joiner.on(",").skipNulls().join(getIndices());
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
    return "{ ID:" + id +
      ", TYP:" + actionRequest.getClass().getSimpleName() +
      ", USR:" + (getLoggedInUser() == null ? (BasicAuthUtils.getBasicAuthUser(getHeaders()) + " (?)") : getLoggedInUser()) +
      ", BRS:" + !Strings.isNullOrEmpty(headers.get("User-Agent")) +
      ", ACT:" + action +
      ", OA:" + getRemoteAddress() +
      ", IDX:" + theIndices +
      ", MET:" + request.method() +
      ", PTH:" + request.path() +
      ", CNT:" + (logger.isDebugEnabled() ? content : "<OMITTED, LENGTH=" + getContent().length() + ">") +
      ", HDR:" + theHeaders +
      ", EFF:" + sideEffects.size() +
      ", HIS:" + hist +
      " }";
  }


  public class RRContextException extends ElasticsearchException {
    public RRContextException(String reason) {
      super(reason);
    }
  }

}
