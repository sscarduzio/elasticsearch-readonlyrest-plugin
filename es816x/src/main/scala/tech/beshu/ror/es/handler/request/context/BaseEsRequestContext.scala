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
package tech.beshu.ror.es.handler.request.context

import eu.timepit.refined.auto.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.{CompositeIndicesRequest, IndicesRequest}
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullLocalDataStreamWithAliases
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RCUtils
import tech.beshu.ror.utils.RefinedUtils.*

import java.time.Instant

abstract class BaseEsRequestContext[B <: BlockContext](esContext: EsContext,
                                                       clusterService: RorClusterService)
  extends RequestContext with Logging {

  override type BLOCK_CONTEXT = B

  private val restRequest = esContext.channel.request()

  override val rorKibanaSessionId: CorrelationId = esContext.correlationId

  override val timestamp: Instant = esContext.timestamp

  override val taskId: Long = esContext.task.getId

  override lazy implicit val id: RequestContext.Id = RequestContext.Id.from(
    sessionCorrelationId = esContext.correlationId,
    requestId = s"${restRequest.hashCode()}#$taskId"
  )

  override lazy val action: Action = esContext.action

  override lazy val headers: Set[Header] = esContext.allHeaders

  override lazy val remoteAddress: Option[Address] =
    Option(restRequest.getHttpChannel)
      .flatMap(c => Option(c.getRemoteAddress))
      .flatMap(isa => Option(isa.getAddress))
      .flatMap(a => Option(a.getHostAddress))
      .map { remoteHost => if (RCUtils.isLocalHost(remoteHost)) RCUtils.LOCALHOST else remoteHost }
      .flatMap(Address.from)

  override lazy val localAddress: Address =
    Option(restRequest.getHttpChannel)
      .flatMap(c => Option(c.getLocalAddress))
      .flatMap(isa => Option(isa.getAddress))
      .flatMap(a => Option(a.getHostAddress))
      .flatMap(Address.from)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname"))

  override lazy val method: Method = Method.fromStringUnsafe(restRequest.method().name())

  override lazy val uriPath: UriPath =
    UriPath
      .from(restRequest.path())
      .getOrElse(UriPath.from(nes("/")))

  override lazy val contentLength: Information = Bytes(Option(restRequest.content()).map(_.length()).getOrElse(0))

  override lazy val `type`: Type = Type {
    val requestClazz = esContext.actionRequest.getClass
    val simpleName = requestClazz.getSimpleName
    simpleName.toLowerCase match {
      case "request" => requestClazz.getName.split("\\.").toList.reverse.headOption.getOrElse(simpleName)
      case _ => simpleName
    }
  }

  override lazy val content: String = Option(restRequest.content()).map(_.utf8ToString()).getOrElse("")

  override lazy val indexAttributes: Set[IndexAttribute] = {
    esContext.actionRequest match {
      case req: IndicesRequest => indexAttributesFrom(req)
      case _ => Set.empty
    }
  }

  override lazy val allIndicesAndAliases: Set[FullLocalIndexWithAliases] =
    clusterService.allIndicesAndAliases

  override lazy val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] =
    clusterService.allRemoteIndicesAndAliases.memoize

  override lazy val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] =
    clusterService.allDataStreamsAndAliases

  override lazy val allRemoteDataStreamsAndAliases: Task[Set[DataStreamName.FullRemoteDataStreamWithAliases]] =
    clusterService.allRemoteDataStreamsAndAliases.memoize

  override lazy val allTemplates: Set[Template] = clusterService.allTemplates

  override lazy val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = clusterService.allSnapshots

  override def snapshotIndices(snapshotName: SnapshotName): Set[ClusterIndexName] = clusterService.snapshotIndices(snapshotName)

  override lazy val isCompositeRequest: Boolean = esContext.actionRequest.isInstanceOf[CompositeIndicesRequest]

  override lazy val isAllowedForDLS: Boolean = {
    esContext.actionRequest match {
      case _ if !isReadOnlyRequest => false
      case sr: SearchRequest if sr.source() == null => true
      case sr: SearchRequest if sr.source.profile || (sr.source.suggest != null && !sr.source.suggest.getSuggestions.isEmpty) => false
      case _ => true
    }
  }

  protected def indexAttributesFrom(request: IndicesRequest): Set[IndexAttribute] = {
    val wildcardOptions = request
      .indicesOptions()
      .wildcardOptions()

    Option.when(wildcardOptions.matchOpen())(IndexAttribute.Opened: IndexAttribute).toCovariantSet ++
      Option.when(wildcardOptions.matchClosed())(IndexAttribute.Closed: IndexAttribute).toCovariantSet
  }
}