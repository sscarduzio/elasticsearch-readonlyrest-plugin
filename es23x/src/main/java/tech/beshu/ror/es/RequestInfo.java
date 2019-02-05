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

import cz.seznam.euphoria.shaded.guava.com.google.common.base.Joiner;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.ObjectArrays;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest;
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest;
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest;
import org.elasticsearch.action.admin.cluster.repositories.verify.VerifyRepositoryRequest;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest;
import org.elasticsearch.action.admin.cluster.snapshots.status.SnapshotsStatusRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.percolate.MultiPercolateRequest;
import org.elasticsearch.action.percolate.PercolateRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.shims.request.RequestInfoShim;
import tech.beshu.ror.commons.utils.RCUtils;
import tech.beshu.ror.commons.utils.ReflecUtils;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static tech.beshu.ror.commons.utils.ReflecUtils.extractStringArrayFromPrivateMethod;

public class RequestInfo implements RequestInfoShim {

  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final ClusterService clusterService;
  private final Long taskId;
  private final IndexNameExpressionResolver indexResolver;
  private final LoggerShim logger;
  private final RestChannel channel;
  private String content = null;
  private Integer contentLength;
  private ESContext context;

  RequestInfo(
      RestChannel channel, Long taskId, String action, ActionRequest actionRequest,
      ClusterService clusterService, ESContext context, IndexNameExpressionResolver indexResolver) {
    this.context = context;
    this.logger = context.logger(getClass());
    this.request = channel.request();
    this.channel = channel;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.indexResolver = indexResolver;
    String tmpID = request.hashCode() + "-" + actionRequest.hashCode();

    if (taskId != null) {
      this.id = tmpID + "#" + taskId;
      this.taskId = taskId;
    }
    else {
      this.id = tmpID;
      this.taskId = null;
    }
  }

  @Override
  public void writeSnapshots(Set<String> newSnapshots) {
    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    String[] newSnapshotsA = newSnapshots.toArray(new String[newSnapshots.size()]);
    if (actionRequest instanceof GetSnapshotsRequest) {
      GetSnapshotsRequest rsr = (GetSnapshotsRequest) actionRequest;
      rsr.snapshots(newSnapshotsA);
      return;
    }

    if (actionRequest instanceof SnapshotsStatusRequest) {
      SnapshotsStatusRequest r = (SnapshotsStatusRequest) actionRequest;
      r.snapshots(newSnapshotsA);
      return;
    }
  }

  @Override
  public void writeRepositories(Set<String> newRepositories) {
    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    String[] newRepositoriesA = newRepositories.toArray(new String[newRepositories.size()]);
    if (actionRequest instanceof GetSnapshotsRequest) {
      GetSnapshotsRequest rsr = (GetSnapshotsRequest) actionRequest;
      rsr.repository(newRepositoriesA[0]);
      return;
    }

    if (actionRequest instanceof SnapshotsStatusRequest) {
      SnapshotsStatusRequest r = (SnapshotsStatusRequest) actionRequest;
      r.repository(newRepositoriesA[0]);
      return;
    }
    if (actionRequest instanceof GetRepositoriesRequest) {
      GetRepositoriesRequest r = (GetRepositoriesRequest) actionRequest;
      r.repositories(newRepositoriesA);
      return;
    }

    if (actionRequest instanceof VerifyRepositoryRequest) {
      VerifyRepositoryRequest r = (VerifyRepositoryRequest) actionRequest;
      r.name(newRepositoriesA[0]);
      return;
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
        content = request.content().toUtf8();
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

    // The most common case first
    if (ar instanceof IndexRequest) {
      IndexRequest ir = (IndexRequest) ar;
      indices = ir.indices();
    }

    // CompositeIndicesRequests
    else if (ar instanceof MultiGetRequest) {
      MultiGetRequest cir = (MultiGetRequest) ar;

      for (MultiGetRequest.Item ir : cir.getItems()) {
        indices = ObjectArrays.concat(indices, ir.indices(), String.class);
      }
    }

    else if (ar instanceof MultiSearchRequest) {
      MultiSearchRequest cir = (MultiSearchRequest) ar;

      for (SearchRequest ir : cir.requests()) {
        indices = ObjectArrays.concat(indices, ir.indices(), String.class);
      }
    }

    else if (ar instanceof MultiTermVectorsRequest) {
      MultiTermVectorsRequest cir = (MultiTermVectorsRequest) ar;

      for (TermVectorsRequest ir : cir.getRequests()) {
        indices = ObjectArrays.concat(indices, ir.indices(), String.class);
      }
    }

    else if (ar instanceof BulkRequest) {
      BulkRequest cir = (BulkRequest) ar;

      for (ActionRequest ir : cir.requests()) {
        String[] docIndices;
        if (ir instanceof IndexRequest) {
          docIndices = ((IndexRequest) ir).indices();
        }
        else if (ir instanceof UpdateRequest) {
          docIndices = ((UpdateRequest) ir).indices();
        }
        else if (ir instanceof DeleteRequest) {
          docIndices = ((DeleteRequest) ir).indices();
        }
        else {
          docIndices = extractStringArrayFromPrivateMethod("indices", ir, context);
          if (docIndices.length == 0) {
            docIndices = extractStringArrayFromPrivateMethod("index", ir, context);
          }
        }
        indices = ObjectArrays.concat(indices, docIndices, String.class);
      }
    }

    else if (ar instanceof DeleteRequest) {
      DeleteRequest ir = (DeleteRequest) ar;
      indices = ir.indices();
    }

    else if (ar instanceof IndicesAliasesRequest) {
      IndicesAliasesRequest ir = (IndicesAliasesRequest) ar;
      Set<String> indicesSet = ir.getAliasActions().stream().map(x -> Sets.newHashSet(x.indices()))
                                 .flatMap(Collection::stream)
                                 .collect(Collectors.toSet());
      indices = (String[]) indicesSet.toArray();
    }

    else if (ar instanceof PercolateRequest) {
      PercolateRequest pr = (PercolateRequest) ar;
      indices = pr.indices();
    }

    else if (ar instanceof MultiPercolateRequest) {
      MultiPercolateRequest pr = (MultiPercolateRequest) ar;
      indices = pr.indices();
    }

    // CompositeIndicesRequests
    else if (ar instanceof CompositeIndicesRequest) {
      logger.error(
          "Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! "
              + ar.getClass().getSimpleName());
    }

    // Particular case because bug: https://github.com/elastic/elasticsearch/issues/28671
    else if (ar instanceof RestoreSnapshotRequest) {
      RestoreSnapshotRequest rsr = (RestoreSnapshotRequest) ar;
      indices = rsr.indices();
    }

    // Last resort
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
  public Set<String> extractSnapshots() {
    if (actionRequest instanceof GetSnapshotsRequest) {
      GetSnapshotsRequest rsr = (GetSnapshotsRequest) actionRequest;
      return Sets.newHashSet(rsr.snapshots());
    }
    else if (actionRequest instanceof CreateSnapshotRequest) {
      CreateSnapshotRequest r = (CreateSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.snapshot());
    }
    else if (actionRequest instanceof DeleteSnapshotRequest) {
      DeleteSnapshotRequest r = (DeleteSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.snapshot());
    }
    else if (actionRequest instanceof RestoreSnapshotRequest) {
      RestoreSnapshotRequest r = (RestoreSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.snapshot());
    }
    else if (actionRequest instanceof SnapshotsStatusRequest) {
      SnapshotsStatusRequest r = (SnapshotsStatusRequest) actionRequest;
      return Sets.newHashSet(r.snapshots());
    }
    return Collections.emptySet();
  }

  @Override
  public Set<String> extractRepositories() {
    if (actionRequest instanceof GetSnapshotsRequest) {
      GetSnapshotsRequest rsr = (GetSnapshotsRequest) actionRequest;
      return Sets.newHashSet(rsr.repository());
    }
    else if (actionRequest instanceof CreateSnapshotRequest) {
      CreateSnapshotRequest r = (CreateSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.repository());
    }
    else if (actionRequest instanceof DeleteSnapshotRequest) {
      DeleteSnapshotRequest r = (DeleteSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.repository());
    }
    else if (actionRequest instanceof RestoreSnapshotRequest) {
      RestoreSnapshotRequest r = (RestoreSnapshotRequest) actionRequest;
      return Sets.newHashSet(r.repository());
    }
    else if (actionRequest instanceof SnapshotsStatusRequest) {
      SnapshotsStatusRequest r = (SnapshotsStatusRequest) actionRequest;
      return Sets.newHashSet(r.repository());
    }

    // Specific to repositories
    else if (actionRequest instanceof PutRepositoryRequest) {
      PutRepositoryRequest r = (PutRepositoryRequest) actionRequest;
      return Sets.newHashSet(r.name());
    }
    else if (actionRequest instanceof GetRepositoriesRequest) {
      GetRepositoriesRequest r = (GetRepositoriesRequest) actionRequest;
      return Sets.newHashSet(r.repositories());
    }
    else if (actionRequest instanceof DeleteRepositoryRequest) {
      DeleteRepositoryRequest r = (DeleteRepositoryRequest) actionRequest;
      return Sets.newHashSet(r.name());
    }
    else if (actionRequest instanceof VerifyRepositoryRequest) {
      VerifyRepositoryRequest r = (VerifyRepositoryRequest) actionRequest;
      return Sets.newHashSet(r.name());
    }

    return Collections.emptySet();
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
  public String extractLocalAddress() {
    String remoteHost = ((InetSocketAddress) request.getLocalAddress()).getAddress().getHostAddress();
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

      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        @SuppressWarnings("unchecked")
        Set<Field> fields = ReflecUtils.getAllFields(bsr.shardId().getClass(), field -> field != null && field.getName().equals("index"));
        fields.stream().forEach(f -> {
          f.setAccessible(true);
          try {
            f.set(bsr.shardId(), new Index(singleIndex));
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
    // #TODO this is not possible in 2.x apparently
    // hMap.keySet().forEach(k -> threadPool.getThreadContext().addResponseHeader(k, hMap.get(k)));
  }

  @Override
  public Set<String> extractAllIndicesAndAliases() {
    return clusterService.state().metaData().getAliasAndIndexLookup().keySet();
  }

  @Override
  public boolean involvesIndices() {
    return actionRequest instanceof IndicesRequest ||
        actionRequest instanceof CompositeIndicesRequest ||
        // Necessary because it won't implement IndicesRequest as it should (bug: https://github.com/elastic/elasticsearch/issues/28671)
        actionRequest instanceof RestoreSnapshotRequest;
  }

  @Override
  public boolean extractIsReadRequest() {
    return RCUtils.isReadRequest(action);
  }

  @Override
  public boolean extractIsAllowedForDLS() {
    // NOT IMPLEMENTED IN 2.x
    return extractIsReadRequest();
  }

  @Override
  public boolean extractIsCompositeRequest() {
    return actionRequest instanceof CompositeIndicesRequest;
  }

  @Override
  public void writeToThreadContextHeader(String key, String value) {
    // NOT IMPLEMENTED IN 2.x
  }

  @Override
  public String consumeThreadContextHeader(String key) {
    // NOT IMPLEMENTED IN 2.x
    return null;
  }
}