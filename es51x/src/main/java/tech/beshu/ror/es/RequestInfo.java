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
package tech.beshu.ror.es;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.reflections.ReflectionUtils;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.shims.request.RequestInfoShim;
import tech.beshu.ror.commons.utils.RCUtils;
import tech.beshu.ror.commons.utils.ReflecUtils;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static tech.beshu.ror.commons.utils.ReflecUtils.extractStringArrayFromPrivateMethod;
import static tech.beshu.ror.commons.utils.ReflecUtils.invokeMethodCached;

public class RequestInfo implements RequestInfoShim {

  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final ClusterService clusterService;
  private final Long taskId;
  private final IndexNameExpressionResolver indexResolver;
  private final ThreadPool threadPool;
  private final LoggerShim logger;
  private final RestChannel channel;
  private String content = null;
  private Integer contentLength;
  private ESContext context;

  RequestInfo(
    RestChannel channel, String action, ActionRequest actionRequest,
    ClusterService clusterService, ThreadPool threadPool, ESContext context, IndexNameExpressionResolver indexResolver) {
    this.context = context;
    this.logger = context.logger(getClass());
    this.threadPool = threadPool;
    this.request = channel.request();
    this.channel = channel;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
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
  }

  @Override
  public String extractType() {
    return actionRequest.getClass().getSimpleName();
  }

  @Override
  public Integer getContentLength() {
    if (contentLength == null) {
      BytesReference cnt = request.content();
      if (cnt == null) {
        contentLength = 0;
      }
      else {
        contentLength = request.content().length();
      }
    }
    return contentLength;
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
  public Set<String> extractIndexMetadata(String index) {
    SortedMap<String, AliasOrIndex> lookup = clusterService.state().metaData().getAliasAndIndexLookup();
    return lookup.get(index).getIndices().stream().map(IndexMetaData::getIndexUUID).collect(Collectors.toSet());
  }

  @Override
  public Long extractTaskId() {
    return taskId;
  }

  public RestChannel getChannel() {
    return channel;
  }

  @Override
  public Integer extractContentLength() {
    if (contentLength == null) {
      BytesReference cnt = request.content();
      if (cnt == null) {
        contentLength = 0;
      }
      else {
        contentLength = request.content().length();
      }
    }
    return contentLength;
  }

  @Override
  public String extractContent() {
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
  public String extractMethod() {
    return request.method().name();
  }

  @Override
  public String extractURI() {
    return request.uri();
  }

  @Override
  public Set<String> extractIndices() {

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
      for (ActionRequest x : cir.requests()) {
        if (x instanceof IndicesRequest) {
          IndicesRequest ir = (IndicesRequest) x;
          indices = ArrayUtils.concat(indices, ir.indices(), String.class);
        }
      }
    }

    else if (ar instanceof IndicesRequest) {
      IndicesRequest ir = (IndicesRequest) ar;
      indices = ir.indices();
    }

    else if ("ReindexRequest".equals(ar.getClass().getSimpleName())) {
      // Using reflection otherwise need to create another sub-project
      try {
        SearchRequest sr = (SearchRequest) invokeMethodCached(ar, ar.getClass(), "getSearchRequest");
        IndexRequest ir = (IndexRequest) invokeMethodCached(ar, ar.getClass(), "getDestination");
        indices = ArrayUtils.concat(sr.indices(), ir.indices(), String.class);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    else if (ar instanceof CompositeIndicesRequest) {
      logger.error(
        "Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! "
          + ar.getClass().getSimpleName());
    }

    else {
      indices = extractStringArrayFromPrivateMethod("indices", ar, context);
      if (indices == null || indices.length == 0) {
        indices = extractStringArrayFromPrivateMethod("index", ar, context);
      }
    }

    if (indices == null) {
      indices = new String[0];
    }

    Set<String> indicesSet = Sets.newHashSet(indices);

    if (logger.isDebugEnabled()) {
      String idxs = String.join(",", indicesSet);
      logger.debug("Discovered indices: " + idxs);
    }

    return indicesSet;
  }

  @Override
  public String extractAction() {
    return action;
  }

  @Override
  public String extractId() {
    return id;
  }

  @Override
  public Map<String, String> extractRequestHeaders() {
    final Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    request.headers().forEach(k -> {
      h.put(k.getKey(), k.getValue());
    });
    return h;
  }


  @Override
  public String extractRemoteAddress() {
    String remoteHost = ((InetSocketAddress) request.getRemoteAddress()).getAddress().getHostAddress();
    // Make sure we recognize localhost even when IPV6 is involved
    if (RCUtils.isLocalHost(remoteHost)) {
      remoteHost = RCUtils.LOCALHOST;
    }
    return remoteHost;
  }

  @Override
  public void writeIndices(Set<String> newIndices) {
    // Setting indices by reflection..
    newIndices.remove("<no-index>");
    newIndices.remove("");

    // Best case, this request is designed to have indices replaced.
    if (actionRequest instanceof IndicesRequest.Replaceable) {
      ((IndicesRequest.Replaceable) actionRequest).indices(newIndices.toArray(new String[newIndices.size()]));
      return;
    }

    // This should not be necessary anymore because nowadays we either allow or forbid write requests.
    if (actionRequest instanceof BulkShardRequest) {
      BulkShardRequest bsr = (BulkShardRequest) actionRequest;
      String singleIndex = newIndices.iterator().next();
      String uuid = extractIndexMetadata(singleIndex).iterator().next();
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        @SuppressWarnings("unchecked")
        Set<Field> fields = ReflectionUtils.getAllFields(bsr.shardId().getClass(), ReflectionUtils.withName("index"));
        fields.stream().forEach(f -> {
          f.setAccessible(true);
          try {
            f.set(bsr.shardId(), new Index(singleIndex, uuid));
          } catch (Throwable e) {
            e.printStackTrace();
          }
        });
        return null;
      });
    }

    if (actionRequest instanceof MultiSearchRequest) {
      // If it's an empty MSR, we are ok
      MultiSearchRequest msr = (MultiSearchRequest) actionRequest;
      for (SearchRequest sr : msr.requests()) {

        // This contains global indices
        if (sr.indices().length == 0 || Sets.newHashSet(sr.indices()).contains("*")) {
          sr.indices(newIndices.toArray(new String[newIndices.size()]));
          continue;
        }

        // This transforms wildcards and aliases in concrete indices
        Set<String> expandedSrIndices = getExpandedIndices(Sets.newHashSet(sr.indices()));

        Set<String> remaining = Sets.newHashSet(expandedSrIndices);
        remaining.retainAll(newIndices);

        if (remaining.size() == 0) {
          // contained just forbidden indices, should return zero results
          sr.source(new SearchSourceBuilder().size(0));
          continue;
        }
        if (remaining.size() == expandedSrIndices.size()) {
          // contained all allowed indices
          continue;
        }
        // some allowed indices were there, restrict query to those
        sr.indices(remaining.toArray(new String[remaining.size()]));
      }
      // All the work is done - no need for reflection
      return;
    }

    if (actionRequest instanceof MultiGetRequest) {
      MultiGetRequest mgr = (MultiGetRequest) actionRequest;
      Iterator<MultiGetRequest.Item> it = mgr.getItems().iterator();
      while (it.hasNext()) {
        MultiGetRequest.Item item = it.next();
        // One item contains just an index, but can be an alias
        Set<String> indices = getExpandedIndices(Sets.newHashSet(item.indices()));
        indices.retainAll(newIndices);
        if (indices.isEmpty()) {
          it.remove();
        }
      }
      // All the work is done - no need for reflection
      return;
    }

    if (actionRequest instanceof IndicesAliasesRequest) {
      IndicesAliasesRequest iar = (IndicesAliasesRequest) actionRequest;
      Iterator<IndicesAliasesRequest.AliasActions> it = iar.getAliasActions().iterator();
      while (it.hasNext()) {
        IndicesAliasesRequest.AliasActions act = it.next();
        Set<String> indices = getExpandedIndices(Sets.newHashSet(act.indices()));
        indices.retainAll(newIndices);
        if (indices.isEmpty()) {
          it.remove();
          continue;
        }
        act.indices(indices.toArray(new String[indices.size()]));
      }
      // All the work is done - no need for reflection
      return;
    }

    // Optimistic reflection attempt
    boolean okSetResult = ReflecUtils.setIndices(actionRequest, Sets.newHashSet("index", "indices"), newIndices, logger);

    if (okSetResult) {
      if (logger.isDebugEnabled()) {
        logger.debug("REFLECTION: success changing indices: " + newIndices + " correctly set as " + extractIndices());
      }
    }
    else {
      logger.error("REFLECTION: Failed to set indices for type " + actionRequest.getClass().getSimpleName() +
                     "  in req id: " + extractId());
    }
  }

  @Override
  public void writeResponseHeaders(Map<String, String> hMap) {
    hMap.keySet().forEach(k -> threadPool.getThreadContext().addResponseHeader(k, hMap.get(k)));
  }

  @Override
  public Set<String> extractAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  @Override
  public boolean involvesIndices() {
    return actionRequest instanceof IndicesRequest || actionRequest instanceof CompositeIndicesRequest;
  }

  @Override
  public boolean extractIsReadRequest() {
    return RCUtils.isReadRequest(action);
  }

  @Override
  public boolean extractIsCompositeRequest() {
    return actionRequest instanceof CompositeIndicesRequest;
  }

}
