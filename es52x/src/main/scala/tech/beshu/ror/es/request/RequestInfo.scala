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
package tech.beshu.ror.es.request

import java.net.InetSocketAddress
import java.util.UUID

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
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest
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
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import org.reflections.ReflectionUtils
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.request.RequestInfoShim
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.ExtractedIndices.RegularIndices
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.{ExtractedIndices, WriteResult}
import tech.beshu.ror.es.utils.ClusterServiceHelper._
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.{RCUtils, ReflecUtils}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class RequestInfo(channel: RestChannel, taskId: Long, action: String, actionRequest: ActionRequest,
                  clusterService: ClusterService, threadPool: ThreadPool)
  extends RequestInfoShim with Logging {

  private val request = channel.request()

  override val extractType: String = actionRequest.getClass.getSimpleName

  override def extractIndexMetadata(index: String): Set[String] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(index).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override val extractTaskId: Long = taskId

  override lazy val extractContentLength: Int = if (request.content == null) 0 else request.content().length()

  override lazy val extractContent: String = if (request.content == null) "" else request.content().utf8ToString()

  override lazy val extractMethod: String = request.method().name()

  override val extractPath: String = request.path()

  override val involvesIndices: Boolean = {
    actionRequest.isInstanceOf[IndicesRequest] || actionRequest.isInstanceOf[CompositeIndicesRequest] ||
      // Necessary because it won't implement IndicesRequest as it should (bug: https://github.com/elastic/elasticsearch/issues/28671)
      actionRequest.isInstanceOf[RestoreSnapshotRequest] ||
      actionRequest.isInstanceOf[GetIndexTemplatesRequest] || actionRequest.isInstanceOf[PutIndexTemplateRequest] || actionRequest.isInstanceOf[DeleteIndexTemplateRequest]
  }

  override lazy val extractIndices: ExtractedIndices = {
    val extractedIndices = actionRequest match {
      case ar: PutIndexTemplateRequest =>
        RegularIndices {
          indicesFromPatterns(clusterService, ar.indices.asSafeSet)
            .flatMap { case (pattern, relatedIndices) => if (relatedIndices.nonEmpty) relatedIndices else Set(pattern) }
            .toSet
        }
      case ar: IndexRequest => // The most common case first
        RegularIndices(ar.indices.asSafeSet)
      case ar: MultiGetRequest =>
        RegularIndices(ar.getItems.asScala.flatMap(_.indices.asSafeSet).toSet)
      case ar: MultiSearchRequest =>
        RegularIndices(ar.requests().asScala.flatMap(_.indices.asSafeSet).toSet)
      case ar: MultiTermVectorsRequest =>
        RegularIndices(ar.getRequests.asScala.flatMap(_.indices.asSafeSet).toSet)
      case ar: BulkRequest =>
        RegularIndices(ar.requests().asScala.collect { case ir: IndicesRequest => ir}.flatMap(_.indices.asSafeSet).toSet)
      case ar: DeleteRequest =>
        RegularIndices(ar.indices.asSafeSet)
      case ar: IndicesAliasesRequest =>
        RegularIndices(ar.getAliasActions.asScala.flatMap(_.indices.asSafeSet).toSet)
      case ar: GetSettingsRequest =>
        RegularIndices(ar.indices.asSafeSet)
      case ar: CompositeIndicesRequest =>
        logger.error(s"Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! ${ar.getClass.getSimpleName}")
        RegularIndices(Set.empty[String])
      case ar: RestoreSnapshotRequest => // Particular case because bug: https://github.com/elastic/elasticsearch/issues/28671
        RegularIndices(ar.indices.asSafeSet)
      case ar =>
        RegularIndices {
          val indices = extractStringArrayFromPrivateMethod("indices", ar).asSafeSet
          if (indices.isEmpty) extractStringArrayFromPrivateMethod("index", ar).asSafeSet
          else indices
        }
    }
    logger.debug(s"Discovered indices: ${extractedIndices.indices.mkString(",")}")
    extractedIndices
  }

  override lazy val extractTemplateIndicesPatterns: Set[String] = {
    val patterns = actionRequest match {
      case ar: GetIndexTemplatesRequest =>
        val templates = ar.names.asSafeSet
        if (templates.isEmpty) getIndicesPatternsOfTemplates(clusterService)
        else getIndicesPatternsOfTemplates(clusterService, templates)
      case ar: PutIndexTemplateRequest =>
        ar.indices.asSafeSet
      case ar: DeleteIndexTemplateRequest =>
        getIndicesPatternsOfTemplate(clusterService, ar.name())
      case _ if extractPath.startsWith("/_cat/templates") =>
        Option(request.param("name")) match {
          case Some(templateName) => getIndicesPatternsOfTemplate(clusterService, templateName)
          case None => Set.empty[String]
        }
      case _ =>
        Set.empty[String]
    }
    patterns
  }

  override lazy val extractSnapshots: Set[String] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.snapshots.asSafeSet
      case ar: CreateSnapshotRequest => ar.snapshot.asSafeSet
      case ar: DeleteSnapshotRequest => ar.snapshot.asSafeSet
      case ar: RestoreSnapshotRequest => ar.snapshot.asSafeSet
      case ar: SnapshotsStatusRequest => ar.snapshots.asSafeSet
      case _ => Set.empty[String]
    }
  }

  override lazy val extractRepositories: Set[String] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.repository.asSafeSet
      case ar: CreateSnapshotRequest => ar.repository.asSafeSet
      case ar: DeleteSnapshotRequest => ar.repository.asSafeSet
      case ar: RestoreSnapshotRequest => ar.repository.asSafeSet
      case ar: SnapshotsStatusRequest => ar.repository.asSafeSet
      case ar: PutRepositoryRequest => ar.name.asSafeSet
      case ar: GetRepositoriesRequest => ar.repositories.asSafeSet
      case ar: DeleteRepositoryRequest => ar.name.asSafeSet
      case ar: VerifyRepositoryRequest => ar.name.asSafeSet
      case _ => Set.empty[String]
    }
  }

  override val extractAction: String = action

  override val extractRequestHeaders: Map[String, String] =
    request
      .headers().asScala
      .map { h => (h.getKey, h.getValue) }
      .toMap

  override val extractRemoteAddress: String = {
    Try {
      val remoteHost = request.getRemoteAddress.asInstanceOf[InetSocketAddress].getAddress.getHostAddress
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
      request.getRemoteAddress.asInstanceOf[InetSocketAddress].getAddress.getHostAddress
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
    s"$tmpID#$taskId"
  }

  override lazy val extractAllIndicesAndAliases: Map[String, Set[String]] = {
    val indices = clusterService.state.metaData.getIndices
    indices
      .keysIt().asScala
      .map { index =>
        val indexMetaData = indices.get(index)
        val indexName = indexMetaData.getIndex.getName
        val aliases: Set[String] = indexMetaData.getAliases.keysIt.asScala.toSet
        (indexName, aliases)
      }
      .toMap
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

  override lazy val extractHasRemoteClusters: Boolean = false

  override def writeSnapshots(newSnapshots: Set[String]): WriteResult[Unit] = {
    if (newSnapshots.isEmpty) return WriteResult.Success(())

    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    actionRequest match {
      case ar: GetSnapshotsRequest =>
        ar.snapshots(newSnapshots.toArray)
      case ar: SnapshotsStatusRequest =>
        ar.snapshots(newSnapshots.toArray)
      case _ =>
    }
    WriteResult.Success(())
  }

  override def writeRepositories(newRepositories: Set[String]): WriteResult[Unit] = {
    if (newRepositories.isEmpty) return WriteResult.Success(())

    // We limit this to read requests, as all the write requests are single-snapshot oriented.
    val newRepositoriesA = newRepositories.toArray
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.repository(newRepositoriesA(0))
      case ar: SnapshotsStatusRequest => ar.repository(newRepositoriesA(0))
      case ar: GetRepositoriesRequest => ar.repositories(newRepositoriesA)
      case ar: VerifyRepositoryRequest => ar.name(newRepositoriesA(0))
      case _ =>
    }
    WriteResult.Success(())
  }

  override def writeResponseHeaders(hMap: Map[String, String]): WriteResult[Unit] = {
    val threadContext = threadPool.getThreadContext
    hMap.foreach { case (key, value) =>
      threadContext.addResponseHeader(key, value)
    }
    WriteResult.Success(())
  }

  override def writeToThreadContextHeaders(hMap: Map[String, String]): WriteResult[Unit] = {
    val threadContext = threadPool.getThreadContext
    hMap.foreach { case (key, value) =>
      threadContext.putHeader(key, value)
    }
    WriteResult.Success(())
  }

  override def writeIndices(newIndices: Set[String]): WriteResult[Unit] = {
    val indices = newIndices.filter(i => i != "" && i != "<no-index>").toList
    if (indices.isEmpty) return WriteResult.Success(())

    actionRequest match {
      case _: IndicesRequest.Replaceable if extractPath.startsWith("/_cat/templates") =>
        // workaround for filtering templates of /_cat/templates action
        WriteResult.Success(())
      case ar: IndicesRequest.Replaceable => // Best case, this request is designed to have indices replaced.
        ar.indices(indices: _*)
        WriteResult.Success(())
      case ar: BulkShardRequest => // This should not be necessary anymore because nowadays we either allow or forbid write requests.
        val singleIndex = indices.head
        val uuid = extractIndexMetadata(singleIndex).toList.head
        ReflectionUtils
          .getAllFields(ar.shardId().getClass, ReflectionUtils.withName("index")).asScala
          .foreach { f =>
            f.setAccessible(true)
            Try(f.set(ar.shardId(), new Index(singleIndex, uuid))) match {
              case Failure(ex) => logger.error("Cannot set index", ex)
              case Success(_) =>
            }
          }
        WriteResult.Success(())
      case ar: MultiSearchRequest =>
        ar.requests().asScala.foreach { sr =>
          if (sr.indices.asSafeSet.isEmpty || sr.indices.asSafeSet.contains("*")) {
            sr.indices(indices: _*)
          } else {
            // This transforms wildcards and aliases in concrete indices
            val expandedSrIndices = getExpandedIndices(sr.indices.asSafeSet)
            val remaining = expandedSrIndices.intersect(indices.toSet)

            if (remaining.isEmpty) { // contained just forbidden indices, should return zero results
              sr.source(new SearchSourceBuilder().size(0))
            } else if (remaining.size == expandedSrIndices.size) { // contained all allowed indices
              // nothing to do
            } else {
              // some allowed indices were there, restrict query to those
              sr.indices(remaining.toList: _*)
            }
          }
        }
        WriteResult.Success(())
      case ar: MultiGetRequest =>
        val it = ar.getItems.iterator
        while (it.hasNext) {
          val item = it.next
          // One item contains just an index, but can be an alias
          val expandedIndices = getExpandedIndices(item.indices.asSafeSet)
          val remaining = expandedIndices.intersect(indices.toSet)
          if (remaining.isEmpty) it.remove()
        }
        WriteResult.Success(())
      case ar: IndicesAliasesRequest =>
        val it = ar.getAliasActions.iterator
        while (it.hasNext) {
          val act = it.next
          val expandedIndices = getExpandedIndices(act.indices.asSafeSet)
          val remaining = expandedIndices.intersect(indices.toSet)
          if (remaining.isEmpty) {
            it.remove()
          } else {
            act.indices(remaining.toList: _*)
          }
        }
        WriteResult.Success(())
      case ar: GetIndexTemplatesRequest =>
        val requestTemplateNames = ar.names.asSafeSet
        val allowedTemplateNames = findTemplatesOfIndices(clusterService, indices.toSet)
        val templateNamesToReturn =
          if (requestTemplateNames.isEmpty) {
            allowedTemplateNames
          } else {
            MatcherWithWildcardsScalaAdapter
              .create(requestTemplateNames)
              .filter(allowedTemplateNames)
          }
        if (templateNamesToReturn.isEmpty) {
          // hack! there is no other way to return empty list of templates (at the moment should not be used, but
          // I leave it as a protection
          WriteResult.Failure
        } else {
          ar.names(templateNamesToReturn.toList: _*)
          WriteResult.Success(())
        }
      case _ =>
        // Optimistic reflection attempt
        val okSetResult = ReflecUtils.setIndices(actionRequest, Set("index", "indices").asJava, indices.toSet.asJava)
        if (okSetResult) {
          logger.debug(s"REFLECTION: success changing indices: $indices correctly set as $extractIndices")
          WriteResult.Success(())
        } else {
          logger.error(s"REFLECTION: Failed to set indices for type ${actionRequest.getClass.getSimpleName} in req id: $extractId")
          WriteResult.Failure
        }
    }
  }

  override def writeTemplatesOf(indices: Set[String]): WriteResult[Unit] = {
    actionRequest match {
      case ar: GetIndexTemplatesRequest =>
        val requestTemplateNames = ar.names.asSafeSet
        val allowedTemplateNames = findTemplatesOfIndices(clusterService, indices)
        val templateNamesToReturn =
          if (requestTemplateNames.isEmpty) {
            allowedTemplateNames
          } else {
            MatcherWithWildcardsScalaAdapter
              .create(requestTemplateNames)
              .filter(allowedTemplateNames)
          }
        if (templateNamesToReturn.isEmpty) {
          // hack! there is no other way to return empty list of templates (at the moment should not be used, but
          // I leave it as a protection
          ar.names(UUID.randomUUID + "*")
        } else {
          ar.names(templateNamesToReturn.toList: _*)
        }
      case _ =>
      // ignore
    }
    WriteResult.Success(())
  }
}
