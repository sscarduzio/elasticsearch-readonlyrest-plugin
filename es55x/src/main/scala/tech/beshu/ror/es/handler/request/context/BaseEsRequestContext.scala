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

import java.net.InetSocketAddress
import java.time.Instant

import cats.data.NonEmptyList
import cats.implicits._
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.CompositeIndicesRequest
import org.elasticsearch.action.search.SearchRequest
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.utils.RCUtils

import scala.collection.JavaConverters._

abstract class BaseEsRequestContext[B <: BlockContext](esContext: EsContext,
                                                       clusterService: RorClusterService)
  extends RequestContext with Logging {

  override type BLOCK_CONTEXT = B

  private val restRequest = esContext.channel.request()

  override val timestamp: Instant = Instant.now()

  override val taskId: Long = esContext.task.getId

  override lazy implicit val id: RequestContext.Id = RequestContext.Id(esContext.requestId)

  override lazy val action: Action = Action(esContext.actionType)

  override lazy val headers: Set[Header] = {
    val (authorizationHeaders, otherHeaders) =
      restRequest
        .getHeaders.asScala
        .map { case (name, values) => (name, values.asScala.toSet) }
        .flatMap { case (name, values) =>
          for {
            nonEmptyName <- NonEmptyString.unapply(name)
            nonEmptyValues <- NonEmptyList.fromList(values.toList.flatMap(NonEmptyString.unapply))
          } yield (Header.Name(nonEmptyName), nonEmptyValues)
        }
        .toSeq
        .partition { case (name, _) => name === Header.Name.authorization }
    val headersFromAuthorizationHeaderValues = authorizationHeaders
      .flatMap { case (_, values) =>
        val headersFromAuthorizationHeaderValues = values
          .map(Header.fromAuthorizationValue)
          .toList
          .map(_.map(_.toList))
          .traverse(identity)
          .map(_.flatten)
        headersFromAuthorizationHeaderValues match {
          case Left(error) => throw new IllegalArgumentException(error.show)
          case Right(v) => v
        }
      }
      .toSet
    val restOfHeaders = otherHeaders
      .flatMap { case (name, values) => values.map(new Header(name, _)).toList }
      .toSet
    val restOfHeaderNames = restOfHeaders.map(_.name)
    restOfHeaders ++ headersFromAuthorizationHeaderValues.filter { header => !restOfHeaderNames.contains(header.name) }
  }

  override lazy val remoteAddress: Option[Address] =
    Option(restRequest.getRemoteAddress)
      .collect { case isa: InetSocketAddress => isa }
      .flatMap(isa => Option(isa.getAddress))
      .flatMap(a => Option(a.getHostAddress))
      .map { remoteHost => if (RCUtils.isLocalHost(remoteHost)) RCUtils.LOCALHOST else remoteHost }
      .flatMap(Address.from)

  override lazy val localAddress: Address =
    Option(restRequest.getLocalAddress)
      .collect { case isa: InetSocketAddress => isa }
      .flatMap(isa => Option(isa.getAddress))
      .flatMap(a => Option(a.getHostAddress))
      .flatMap(Address.from)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname"))

  override lazy val method: Method = Method(restRequest.method().name())

  override lazy val uriPath: UriPath =
    UriPath
      .from(restRequest.path())
      .getOrElse(UriPath(NonEmptyString.unsafeFrom("/")))

  override lazy val contentLength: Information = Bytes(if (restRequest.content == null) 0 else restRequest.content().length())

  override lazy val `type`: Type = Type {
    val requestClazz = esContext.actionRequest.getClass
    val simpleName = requestClazz.getSimpleName
    simpleName.toLowerCase match {
      case "request" => requestClazz.getName.split("\\.").toList.reverse.headOption.getOrElse(simpleName)
      case _ => simpleName
    }
  }

  override lazy val content: String = if (restRequest.content == null) "" else restRequest.content().utf8ToString()

  override lazy val allIndicesAndAliases: Set[FullLocalIndexWithAliases] =
    clusterService.allIndicesAndAliases

  override def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] =
    clusterService.allRemoteIndicesAndAliases.memoize

  override lazy val allTemplates: Set[Template] = clusterService.allTemplates

  override lazy val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = clusterService.allSnapshots

  override lazy val isReadOnlyRequest: Boolean = RCUtils.isReadRequest(action.value)

  override lazy val isCompositeRequest: Boolean = esContext.actionRequest.isInstanceOf[CompositeIndicesRequest]

  override lazy val isAllowedForDLS: Boolean = {
    esContext.actionRequest match {
      case _ if !isReadOnlyRequest => false
      case sr: SearchRequest if sr.source() == null => true
      case sr: SearchRequest if sr.source.profile || (sr.source.suggest != null && !sr.source.suggest.getSuggestions.isEmpty) => false
      case _ => true
    }
  }

  protected def indicesOrWildcard(indices: Set[ClusterIndexName]): Set[ClusterIndexName] = {
    if (indices.nonEmpty) indices else Set(ClusterIndexName.Local.wildcard)
  }

  protected def repositoriesOrWildcard(repositories: Set[RepositoryName]): Set[RepositoryName] = {
    if (repositories.nonEmpty) repositories else Set(RepositoryName.all)
  }

  protected def snapshotsOrWildcard(snapshots: Set[SnapshotName]): Set[SnapshotName] = {
    if (snapshots.nonEmpty) snapshots else Set(SnapshotName.all)
  }
}