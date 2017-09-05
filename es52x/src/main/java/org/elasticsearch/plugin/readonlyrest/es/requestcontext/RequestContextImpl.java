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

package org.elasticsearch.plugin.readonlyrest.es.requestcontext;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.es.ThreadRepo;
import org.elasticsearch.plugin.readonlyrest.requestcontext.IndicesRequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RCUtils;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.Transactional;
import org.elasticsearch.plugin.readonlyrest.utils.ReflecUtils;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;

import java.net.InetSocketAddress;
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
public class RequestContextImpl extends RequestContext implements IndicesRequestContext {

  private final LoggerShim logger;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final ClusterService clusterService;
  private final ESContext context;

  private final Long taskId;
  private final IndexNameExpressionResolver indexResolver;
  private final ThreadPool threadPool;
  private String content = null;

  public RequestContextImpl(RestRequest request, String action, ActionRequest actionRequest,
                            ClusterService clusterService, ThreadPool threadPool, ESContext context, IndexNameExpressionResolver indexResolver) {
    super("rc", context);
    this.logger = context.logger(getClass());
    this.request = request;
    this.threadPool = threadPool;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
    this.context = context;
    String tmpID = request.hashCode() + "-" + actionRequest.hashCode();
    Long taskId = ThreadRepo.taskId.get();
    if (taskId != null) {
      this.id = tmpID + "#" + taskId;
      ThreadRepo.taskId.remove();
      this.taskId = taskId;
    }
    else {
      this.id = tmpID;
      this.taskId = null;
    }

    init();
  }

  @Override
  public String getClusterUUID() {
    return clusterService.state().stateUUID();
  }

  @Override
  public String getNodeUUID() {
    return clusterService.state().nodes().getLocalNodeId();
  }

  // Internal
  ActionRequest getUnderlyingRequest() {
    return actionRequest;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public Long getTaskId() {
    return taskId;
  }

  @Override
  public Boolean isReadRequest() {
    if (actionRequest.getClass().isAssignableFrom(WriteRequest.class)) {
      return false;
    }
    return RCUtils.isReadRequest(action);
  }

  @Override
  public String getRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (RCUtils.isLocalHost(remoteHost)) {
      remoteHost = RCUtils.LOCALHOST;
    }
    return remoteHost;
  }

  @Override
  protected Map<String, String> extractRequestHeaders() {
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    request.headers().forEach(k -> {
      h.put(k.getKey(), k.getValue());
    });
    return h;
  }


  @Override
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

  @Override
  public String getType() {
    return actionRequest.getClass().getSimpleName();
  }

  // Internal
  public Set<String> getIndexMetadata(String s) {
    SortedMap<String, AliasOrIndex> lookup = clusterService.state().metaData().getAliasAndIndexLookup();
    return lookup.get(s).getIndices().stream().map(IndexMetaData::getIndexUUID).collect(Collectors.toSet());
  }

  public HttpMethod getMethod() {
    switch (request.method()) {
      case GET:
        return HttpMethod.GET;
      case POST:
        return HttpMethod.POST;
      case PUT:
        return HttpMethod.PUT;
      case DELETE:
        return HttpMethod.DELETE;
      case OPTIONS:
        return HttpMethod.OPTIONS;
      case HEAD:
        return HttpMethod.HEAD;
      default:
        throw context.rorException("Unknown/unsupported http method");
    }
  }

  @Override
  public Set<String> getExpandedIndices(Set<String> ixsSet) {
    if (involvesIndices()) {
      String[] ixs = ixsSet.toArray(new String[ixsSet.size()]);
      IndicesOptions opts = IndicesOptions.strictExpand();

      if (actionRequest instanceof IndicesRequest) {
        opts = ((IndicesRequest) actionRequest).indicesOptions();
      }
      String[] concreteIdxNames = {};
      try {
        concreteIdxNames = indexResolver.concreteIndexNames(clusterService.state(), opts, ixs);
      } catch (IndexNotFoundException infe) {
        if (logger.isDebugEnabled()) {
          logger.debug(Joiner.on(",").join(ixs) + " expands to no known index!");
        }
      } catch (Throwable t) {
        logger.error("error while resolving expanded indices", t);
      }
      return Sets.newHashSet(concreteIdxNames);
      //return new MatcherWithWildcards(ixsSet).filter(getAllIndicesAndAliases());
    }
    throw new ElasticsearchException("Cannot get expanded indices of a non-index request");
  }

  @Override
  public Set<String> getAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  @Override
  protected Boolean extractDoesInvolveIndices() {
    return actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;
  }

  @Override
  protected Transactional<Set<String>> extractTransactionalIndices() {
    return RCTransactionalIndices.mkInstance(this, context);
  }

  @Override
  public void setIndices(final Set<String> newIndices) {
    if (!involvesIndices()) {
      throw context.rorException("cannot set indices of a request that doesn't involve indices: " + this);
    }

    if (newIndices.size() == 0) {
      if (isReadRequest()) {
        throw new ElasticsearchException(
          "Attempted to set indices from [" + Joiner.on(",").join(indices.getInitial()) +
            "] toempty set." +
            ", probably your request matched no index, or was rewritten to nonexistentindices (which would expand to empty set).");
      }
      else {
        throw new ElasticsearchException(
          "Attempted to set indices from [" + Joiner.on(",").join(indices.getInitial()) +
            "] to empty set. " + "In ES, specifying no index is the same as full access, therefore this requestis forbidden.");
      }
    }

    if (newIndices.size() == indices.get().size() && indices.get().containsAll(newIndices)) {
      logger.debug("the indices are the same, won't set anything...");
      return;
    }

    if (isReadRequest()) {
      Set<String> expanded = getExpandedIndices(newIndices);

      // When an index don't expand into one or more indices, it means it does not exist. This is fine.
      if (expanded.isEmpty()) {
        expanded = newIndices;
      }
      indices.mutate(expanded);
    }
    else {
      indices.mutate(newIndices);
    }
  }

  @Override
  public Boolean hasSubRequests() {
    return !SubRequestContext.extractNativeSubrequests(actionRequest).isEmpty();
  }

  @Override
  public Integer scanSubRequests(final ReflecUtils.CheckedFunction<IndicesRequestContext, Optional<IndicesRequestContext>> replacer) {

    List<Object> subRequests = SubRequestContext.extractNativeSubrequests(actionRequest);

    logger.debug("found " + subRequests.size() + " subrequests");

    // Composite request #TODO should we really prevent this?
    if (!involvesIndices()) {
      throw context.rorException("cannot replace indices of a composite request that doesn't involve indices: " + this);
    }

    Iterator<Object> it = subRequests.iterator();
    while (it.hasNext()) {
      IndicesRequestContext i = new SubRequestContext(this, it.next(), context);
      final Optional<IndicesRequestContext> mutatedSubReqO;

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

  @Override
  protected void commitResponseHeaders(Map<String, String> hMap) {
    hMap.keySet().forEach(k -> threadPool.getThreadContext().addResponseHeader(k, hMap.get(k)));
  }

  @Override
  public String getUri() {
    return request.uri();
  }

  @Override
  public String getAction() {
    return action;
  }
}
