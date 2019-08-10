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
package tech.beshu.ror.es

import java.util.UUID
import java.{lang, util}

import com.google.common.collect.{Maps, Sets}
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.verify.VerifyRepositoryRequest
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.status.SnapshotsStatusRequest
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.bulk.{BulkRequest, BulkShardRequest}
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest
import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest, IndicesRequest}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.Index
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import org.reflections.ReflectionUtils
import tech.beshu.ror.es.utils.ClusterServiceHelper.{findTemplatesOfIndices, getIndicesRelatedToTemplates, indicesFromPatterns}
import tech.beshu.ror.shims.request.RequestInfoShim
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ReflecUtils.{extractStringArrayFromPrivateMethod, invokeMethodCached}
import tech.beshu.ror.utils.{RCUtils, ReflecUtils}

import scala.collection.JavaConverters._
import scala.math.Ordering.comparatorToOrdering
import scala.util.{Failure, Success, Try}

class RequestInfo(channel: RestChannel, taskId: lang.Long, action: String, actionRequest: ActionRequest,
                  clusterService: ClusterService, threadPool: ThreadPool, remoteClusterService: RemoteClusterService)
  extends RequestInfoShim with Logging {

  private val request = channel.request()

  override def extractType(): String = actionRequest.getClass.getSimpleName

  override def extractIndexMetadata(index: String): util.Set[String] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(index).getIndices.asScala.map(_.getIndexUUID).toSet.asJava
  }

  override val extractTaskId: lang.Long = taskId

  override lazy val extractContentLength: Integer = if(request.content == null) 0 else request.content().length()

  override lazy val extractContent: String = if(request.content == null) "" else request.content().utf8ToString()

  override lazy val extractMethod: String = request.method().name()

  override val extractURI: String = request.uri()

  override val involvesIndices: Boolean = {
    actionRequest.isInstanceOf[IndicesRequest] || actionRequest.isInstanceOf[CompositeIndicesRequest] ||
      // Necessary because it won't implement IndicesRequest as it should (bug: https://github.com/elastic/elasticsearch/issues/28671)
      actionRequest.isInstanceOf[RestoreSnapshotRequest] ||
      actionRequest.isInstanceOf[GetIndexTemplatesRequest] || actionRequest.isInstanceOf[PutIndexTemplateRequest] || actionRequest.isInstanceOf[DeleteIndexTemplateRequest]
  }

  override lazy val extractIndices: util.Set[String] = {
    val indices = actionRequest match {
      case ar: IndexRequest => // The most common case first
        ar.indices.toSet
      case ar: MultiGetRequest =>
        ar.getItems.asScala.flatMap(_.indices()).toSet
      case ar: MultiSearchRequest =>
        ar.requests().asScala.flatMap(_.indices()).toSet
      case ar: MultiTermVectorsRequest =>
        ar.getRequests.asScala.flatMap(_.indices()).toSet
      case ar: BulkRequest =>
        ar.requests().asScala.flatMap(_.indices()).toSet
      case ar: DeleteRequest =>
        ar.indices().toSet
      case ar: IndicesAliasesRequest =>
        ar.getAliasActions.asScala.flatMap(_.indices()).toSet
      case ar: ReindexRequest => // Buggy cases here onwards
        Try {
          val sr = invokeMethodCached(ar, ar.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
          val ir = invokeMethodCached(ar, ar.getClass, "getDestination").asInstanceOf[IndexRequest]
          sr.indices().toSet ++ ir.indices().toSet
        } fold(
          ex => {
            logger.errorEx(s"cannot extract indices from: $extractMethod $extractURI\n$extractContent", ex)
            Set.empty[String]
          },
          identity
        )
      case ar if ar.getClass.getSimpleName.startsWith("Sql") =>
        // Do noting, we can't do anything about X-Pack SQL queries, as it does not contain indices.
        // todo: The only way we can filter this kind of request is going Lucene level like "filter" rule.
        Set.empty[String]
      case ar: CompositeIndicesRequest =>
        logger.error(s"Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! ${ar.getClass.getSimpleName}")
        Set.empty[String]
      case ar: RestoreSnapshotRequest => // Particular case because bug: https://github.com/elastic/elasticsearch/issues/28671
        ar.indices().toSet
      case ar: PutIndexTemplateRequest =>
        indicesFromPatterns(clusterService, ar.indices.toSet)
      case ar: DeleteIndexTemplateRequest =>
        getIndicesRelatedToTemplates(clusterService, Set(ar.name))
      case ar: GetIndexTemplatesRequest =>
        if(ar.names().isEmpty) Set("*")
        else getIndicesRelatedToTemplates(clusterService, ar.names().toSet)
      case ar =>
        val indices = extractStringArrayFromPrivateMethod("indices", ar).toSet
        if(indices.isEmpty) extractStringArrayFromPrivateMethod("index", ar).toSet
        else indices
    }
    logger.debug(s"Discovered indices: ${indices.mkString(",")}")
    indices.asJava
  }

  override lazy val extractSnapshots: util.Set[String] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.snapshots().toSet.asJava
      case ar: CreateSnapshotRequest => Set(ar.snapshot()).asJava
      case ar: DeleteSnapshotRequest => Set(ar.snapshot()).asJava
      case ar: RestoreSnapshotRequest => Set(ar.snapshot()).asJava
      case ar: SnapshotsStatusRequest => ar.snapshots().toSet.asJava
      case _ => Set.empty[String].asJava
    }
  }

  override lazy val extractRepositories: util.Set[String] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => Set(ar.repository()).asJava
      case ar: CreateSnapshotRequest => Set(ar.repository()).asJava
      case ar: DeleteSnapshotRequest => Set(ar.repository()).asJava
      case ar: RestoreSnapshotRequest => Set(ar.repository()).asJava
      case ar: SnapshotsStatusRequest => Set(ar.repository()).asJava
      case ar: PutRepositoryRequest => Set(ar.name()).asJava
      case ar: GetRepositoriesRequest => ar.repositories().toSet.asJava
      case ar: DeleteRepositoryRequest => Set(ar.name()).asJava
      case ar: VerifyRepositoryRequest => Set(ar.name()).asJava
      case _ => Set.empty[String].asJava
    }
  }

  override val extractAction: String = action

  override val extractRequestHeaders: util.Map[String, String] =
    request
      .getHeaders.asScala
      .map { h => (h._1, h._2.asScala.min(comparatorToOrdering(String.CASE_INSENSITIVE_ORDER))) }
      .asJava

  override val extractRemoteAddress: String = {
    Try {
      val remoteHost = request.getHttpChannel.getRemoteAddress.getAddress.getHostAddress
      // Make sure we recognize localhost even when IPV6 is involved
      if (RCUtils.isLocalHost(remoteHost)) RCUtils.LOCALHOST else remoteHost
    } fold(
      ex => {
        logger.error("Could not extract remote address", ex)
        null
      },
      identity
    )
  }

  override val extractLocalAddress: String = {
    Try {
      request.getHttpChannel.getLocalAddress.getAddress.getHostAddress
    } fold(
      ex => {
        logger.error("Could not extract local address", ex)
        null
      },
      identity
    )
  }

  override val extractId: String = {
    val tmpID = request.hashCode() + "-" + actionRequest.hashCode()
    if (taskId != null) s"$tmpID#$taskId"
    else tmpID
  }

  override lazy val extractAllIndicesAndAliases: util.Set[java.util.Map.Entry[String, util.Set[String]]] = {
    val indices = clusterService.state.metaData.getIndices
    indices
      .keysIt().asScala
      .map { index =>
        val indexMetaData = indices.get(index)
        val indexName = indexMetaData.getIndex.getName
        val aliases: util.Set[String] = Sets.newHashSet(indexMetaData.getAliases.keysIt)
        Maps.immutableEntry(indexName, aliases)
      }
      .toSet
      .asJava
  }

  override val extractIsReadRequest: Boolean = RCUtils.isReadRequest(action)

  override val extractIsAllowedForDLS: Boolean = {
    actionRequest match {
      case _ if !extractIsReadRequest => false
      case sr: SearchRequest if sr.source() == null => true
      case sr: SearchRequest if sr.source.profile || (sr.source.suggest != null && !sr.source.suggest.getSuggestions.isEmpty) => false
      case _ => true
    }
  }

  override val extractIsCompositeRequest: Boolean = actionRequest.isInstanceOf[CompositeIndicesRequest]

  override val extractHasRemoteClusters: Boolean = remoteClusterService.isCrossClusterSearchEnabled

  override def writeSnapshots(newSnapshots: util.Set[String]): Unit = {
    if (newSnapshots.isEmpty) return

    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    actionRequest match {
      case ar: GetSnapshotsRequest =>
        ar.snapshots(newSnapshots.toArray(new Array[String](newSnapshots.size)))
      case ar: SnapshotsStatusRequest =>
        ar.snapshots(newSnapshots.toArray(new Array[String](newSnapshots.size)))
      case _ =>
    }
  }

  override def writeRepositories(newRepositories: util.Set[String]): Unit = {
    if (newRepositories.isEmpty) return

    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    val newRepositoriesA = newRepositories.toArray(new Array[String](newRepositories.size))
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.repository(newRepositoriesA(0))
      case ar: SnapshotsStatusRequest => ar.repository(newRepositoriesA(0))
      case ar: GetRepositoriesRequest => ar.repositories(newRepositoriesA)
      case ar: VerifyRepositoryRequest => ar.name(newRepositoriesA(0))
      case _ =>
    }
  }

  override def writeResponseHeaders(hMap: util.Map[String, String]): Unit = {
    val threadContext = threadPool.getThreadContext
    hMap.asScala.foreach { case (key, value) =>
      threadContext.addResponseHeader(key, value)
    }
  }

  override def writeToThreadContextHeaders(hMap: util.Map[String, String]): Unit = {
    val threadContext = threadPool.getThreadContext
    hMap.asScala.foreach { case (key, value) =>
      threadContext.putHeader(key, value)
    }
  }

  override def writeIndices(newIndices: util.Set[String]): Unit = {
    val indices = newIndices.asScala.filter(i => i != "" && i != "<no-index>").toList
    if (indices.isEmpty) return

    actionRequest match {
      case ar: IndicesRequest.Replaceable => // Best case, this request is designed to have indices replaced.
        ar.indices(indices: _*)
      case ar: BulkShardRequest => // This should not be necessary anymore because nowadays we either allow or forbid write requests.
        val singleIndex = indices.head
        val uuid = extractIndexMetadata(singleIndex).asScala.toList.head
        ReflectionUtils
          .getAllFields(ar.shardId().getClass, ReflectionUtils.withName("index")).asScala
          .foreach { f =>
            f.setAccessible(true)
            Try(f.set(ar.shardId(), new Index(singleIndex, uuid))) match {
              case Failure(ex) => logger.error("Cannot set index", ex)
              case Success(_) =>
            }
          }
      case ar: MultiSearchRequest =>
        ar.requests().asScala.foreach { sr =>
          if (sr.indices.length == 0 || sr.indices().contains("*")) {
            sr.indices(indices: _*)
          } else {
            // This transforms wildcards and aliases in concrete indices
            val expandedSrIndices = getExpandedIndices(sr.indices.toSet.asJava)

            val remaining = Sets.newHashSet(expandedSrIndices)
            remaining.retainAll(indices.asJava)

            if (remaining.size == 0) { // contained just forbidden indices, should return zero results
              sr.source(new SearchSourceBuilder().size(0))
            } else if (remaining.size == expandedSrIndices.size) { // contained all allowed indices
              // nothing to do
            } else {
              // some allowed indices were there, restrict query to those
              sr.indices(remaining.asScala.toList: _*)
            }
          }
        }
      case ar: MultiGetRequest =>
        val it = ar.getItems.iterator
        while (it.hasNext) {
          val item = it.next
          // One item contains just an index, but can be an alias
          val indices = getExpandedIndices(item.indices.toSet.asJava)
          val remaining = indices
          remaining.retainAll(indices)
          if (remaining.isEmpty) it.remove()
        }
      case ar: IndicesAliasesRequest =>
        val it = ar.getAliasActions.iterator
        while (it.hasNext) {
          val act = it.next
          val indices = getExpandedIndices(act.indices().toSet.asJava)
          val remaining = indices
          remaining.retainAll(indices)
          if (remaining.isEmpty) {
            it.remove()
          } else {
            act.indices(remaining.asScala.toList: _*)
          }
        }
      case ar: GetIndexTemplatesRequest =>
        val requestTemplateNames = ar.names().toSet
        val allowedTemplateNames = findTemplatesOfIndices(clusterService, indices.toSet)
        val templateNamesToReturn =
          if (requestTemplateNames.isEmpty) allowedTemplateNames
          else requestTemplateNames.intersect(allowedTemplateNames)
        if (templateNamesToReturn.isEmpty) { // hack! there is no other way to return empty list of templates
          ar.names(UUID.randomUUID + "*")
        } else {
          ar.names(templateNamesToReturn.toList: _*)
        }
      case _ =>
        // Optimistic reflection attempt
        val okSetResult = ReflecUtils.setIndices(actionRequest, Sets.newHashSet("index", "indices"), indices.toSet.asJava)
        if (okSetResult) logger.debug(s"REFLECTION: success changing indices: $indices correctly set as $extractIndices")
        else logger.error(s"REFLECTION: Failed to set indices for type ${actionRequest.getClass.getSimpleName} in req id: $extractId")
    }
  }
}
