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

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.DocWriteRequest;
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
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.util.ArrayUtils;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.reindex.ReindexRequest;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterService;
import org.reflections.ReflectionUtils;
import tech.beshu.ror.es.utils.ClusterServiceHelper;
import tech.beshu.ror.shims.request.RequestInfoShim;
import tech.beshu.ror.utils.RCUtils;
import tech.beshu.ror.utils.ReflecUtils;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static tech.beshu.ror.es.utils.ClusterServiceHelper.findTemplatesOfIndices;
import static tech.beshu.ror.es.utils.ClusterServiceHelper.getIndicesRelatedToTemplates;
import static tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod;
import static tech.beshu.ror.utils.ReflecUtils.invokeMethodCached;

public class RequestInfo implements RequestInfoShim {

  private final Logger logger = LogManager.getLogger(this.getClass());

  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;
  private final String id;
  private final ClusterService clusterService;
  private final Long taskId;
  private final RemoteClusterService remoteClusterService;
  private final ThreadPool threadPool;
  private final RestChannel channel;
  private String content = null;
  private Integer contentLength;

  RequestInfo(RestChannel channel, Long taskId, String action, ActionRequest actionRequest,
      ClusterService clusterService, ThreadPool threadPool, RemoteClusterService remoteClusterService) {
    this.threadPool = threadPool;
    this.request = channel.request();
    this.channel = channel;
    this.action = action;
    this.actionRequest = actionRequest;
    this.clusterService = clusterService;
    this.taskId = taskId;
    this.remoteClusterService = remoteClusterService;
    String tmpID = request.hashCode() + "-" + actionRequest.hashCode();
    if (taskId != null) {
      this.id = tmpID + "#" + taskId;
    }
    else {
      this.id = tmpID;
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

    // The most common case first
    if (ar instanceof IndexRequest) {
      IndexRequest ir = (IndexRequest) ar;
      indices = ir.indices();
    }

    // CompositeIndicesRequests
    else if (ar instanceof MultiGetRequest) {
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
        indices = ArrayUtils.concat(indices, ir.indices(), String.class);
      }
    }

    else if (ar instanceof DeleteRequest) {
      DeleteRequest ir = (DeleteRequest) ar;
      indices = ir.indices();
    }

    else if (ar instanceof IndicesAliasesRequest) {
      IndicesAliasesRequest ir = (IndicesAliasesRequest) ar;
      indices = ir.getAliasActions().stream().map(x -> Sets.newHashSet(x.indices())).flatMap(
          Collection::stream).distinct().toArray(String[]::new);
    }

    // Buggy cases here onwards
    else if (ar instanceof ReindexRequest) {
      // Using reflection otherwise need to create another sub-project
      try {
        SearchRequest sr = (SearchRequest) invokeMethodCached(ar, ar.getClass(), "getSearchRequest");
        IndexRequest ir = (IndexRequest) invokeMethodCached(ar, ar.getClass(), "getDestination");
        indices = ArrayUtils.concat(sr.indices(), ir.indices(), String.class);
      } catch (Exception e) {
        logger.error("cannot extract indices from: " + extractMethod() + " " + extractURI() + "\n" + extractContent(),
            e);
        e.printStackTrace();
      }
    }
    else if (ar.getClass().getSimpleName().startsWith("Sql")) {
      // Do noting, we can't do anything about X-Pack SQL queries, as it does not contain indices.
      // #TODO The only way we can filter this kind of request is going Lucene level like "filter" rule.
    }

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

    else if (ar instanceof PutIndexTemplateRequest) {
      PutIndexTemplateRequest pitr = (PutIndexTemplateRequest) ar;
      indices = ClusterServiceHelper
          .indicesFromPatterns(clusterService, Sets.newHashSet(pitr.indices()))
          .entrySet().stream()
          .flatMap(e -> e.getValue().isEmpty() ? Stream.of(e.getKey()) : e.getValue().stream())
          .toArray(String[]::new);
    }
    else if (ar instanceof DeleteIndexTemplateRequest) {
      DeleteIndexTemplateRequest ditr = (DeleteIndexTemplateRequest) ar;
      indices = getIndicesRelatedToTemplates(clusterService, Sets.newHashSet(ditr.name())).toArray(new String[0]);
    }
    else if (ar instanceof GetIndexTemplatesRequest) {
      GetIndexTemplatesRequest gitr = (GetIndexTemplatesRequest) ar;
      Set<String> requestedTemplateNames = Sets.newHashSet(gitr.names());
      if(requestedTemplateNames.isEmpty()) {
        indices = new String[] { "*" };
      } else {
        indices = getIndicesRelatedToTemplates(clusterService, requestedTemplateNames).toArray(new String[0]);
      }
    }

    // Last resort
    else {
      indices = extractStringArrayFromPrivateMethod("indices", ar);
      if (indices == null || indices.length == 0) {
        indices = extractStringArrayFromPrivateMethod("index", ar);
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
  public void writeSnapshots(Set<String> newSnapshots) {
    if(newSnapshots.isEmpty()) return;

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
    if(newRepositories.isEmpty()) return;

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
    request.getHeaders().keySet().forEach(k -> {
      if (request.getAllHeaderValues(k).isEmpty()) {
        return;
      }
      h.put(k, request.getAllHeaderValues(k).iterator().next());
    });
    return h;
  }

  @Override
  public String extractLocalAddress() {
    try {
      String remoteHost = request.getHttpChannel().getLocalAddress().getAddress().getHostAddress();
      return remoteHost;
    } catch (Exception e) {
      logger.error("Could not extract local address", e);
      return null;
    }

  }

  @Override
  public String extractRemoteAddress() {
    try {
      String remoteHost = request.getHttpChannel().getRemoteAddress().getAddress().getHostAddress();
      // Make sure we recognize localhost even when IPV6 is involved
      if (RCUtils.isLocalHost(remoteHost)) {
        remoteHost = RCUtils.LOCALHOST;
      }
      return remoteHost;
    } catch (Exception e) {
      logger.error("Could not extract remote address", e);
      return null;
    }
  }

  @Override
  public void writeIndices(Set<String> newIndices) {
    if(newIndices.isEmpty()) return;

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
        @SuppressWarnings("unchecked") Set<Field> fields = ReflectionUtils.getAllFields(bsr.shardId().getClass(),
            ReflectionUtils.withName("index"));
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
        Set<String> remaining = indices;
        remaining.retainAll(newIndices);
        if (remaining.isEmpty()) {
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
        Set<String> remaining = indices;
        remaining.retainAll(newIndices);
        if (remaining.isEmpty()) {
          it.remove();
          continue;
        }
        act.indices(remaining.toArray(new String[remaining.size()]));
      }
      // All the work is done - no need for reflection
      return;
    }

    if(actionRequest instanceof GetIndexTemplatesRequest) {
      GetIndexTemplatesRequest gitr = (GetIndexTemplatesRequest) actionRequest;
      Set<String> requestTemplateNames = Sets.newHashSet(gitr.names());
      Set<String> allowedTemplateNames = findTemplatesOfIndices(clusterService, newIndices);
      Set<String> templateNamesToReturn =
          requestTemplateNames.isEmpty()
              ? allowedTemplateNames
              : Sets.intersection(requestTemplateNames, allowedTemplateNames);
      if(templateNamesToReturn.isEmpty()) {
        // hack! there is no other way to return empty list of templates
        gitr.names(UUID.randomUUID() + "*");
      } else {
        gitr.names(templateNamesToReturn.toArray(new String[0]));
      }
      return;
    }

    // Optimistic reflection attempt
    boolean okSetResult = ReflecUtils.setIndices(actionRequest, Sets.newHashSet("index", "indices"), newIndices);

    if (okSetResult) {
      if (logger.isDebugEnabled()) {
        logger.debug("REFLECTION: success changing indices: " + newIndices + " correctly set as " + extractIndices());
      }
    }
    else {
      logger.error(
          "REFLECTION: Failed to set indices for type " + actionRequest.getClass().getSimpleName() + "  in req id: "
              + extractId());
    }
  }

  @Override
  public void writeResponseHeaders(Map<String, String> hMap) {
    hMap.keySet().forEach(k -> {
      String val = hMap.get(k);
      threadPool.getThreadContext().addResponseHeader(k, val, v -> val);
    });
  }

  @Override
  public Set<Map.Entry<String, Set<String>>> extractAllIndicesAndAliases() {
    ImmutableOpenMap<String, IndexMetaData> indices = clusterService.state().metaData().getIndices();
    HashSet<Map.Entry<String, Set<String>>> result = Sets.newHashSet();
    indices.keysIt().forEachRemaining(key -> {
      IndexMetaData indexMetaData = indices.get(key);
      String indexName = indexMetaData.getIndex().getName();
      Set<String> aliases = Sets.newHashSet(indexMetaData.getAliases().keysIt());
      result.add(Maps.immutableEntry(indexName, aliases));
    });
    return result;
  }

  @Override
  public boolean involvesIndices() {
    return actionRequest instanceof IndicesRequest ||
        actionRequest instanceof CompositeIndicesRequest ||
        // Necessary because it won't implement IndicesRequest as it should (bug: https://github.com/elastic/elasticsearch/issues/28671)
        actionRequest instanceof RestoreSnapshotRequest ||
        actionRequest instanceof GetIndexTemplatesRequest || actionRequest instanceof PutIndexTemplateRequest || actionRequest instanceof DeleteIndexTemplateRequest;
  }

  @Override
  public boolean extractIsReadRequest() {
    return RCUtils.isReadRequest(action);
  }

  @Override
  public boolean extractIsAllowedForDLS() {
    if (!extractIsReadRequest()) {
      return false;
    }
    if (actionRequest instanceof SearchRequest) {
      SearchRequest sr = (SearchRequest) actionRequest;
      if (sr.source() == null) {
        return true;
      }
      if (sr.source().profile() || (sr.source().suggest() != null
          && !sr.source().suggest().getSuggestions().isEmpty())) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean extractIsCompositeRequest() {
    return actionRequest instanceof CompositeIndicesRequest;
  }

  @Override
  public void writeToThreadContextHeaders(Map<String, String> hMap) {
    ThreadContext threadContext = threadPool.getThreadContext();
    hMap.keySet().forEach(k -> threadContext.putHeader(k, hMap.get(k)));
  }

  @Override
  public boolean extractHasRemoteClusters() {
    return remoteClusterService.isCrossClusterSearchEnabled();
  }

}
