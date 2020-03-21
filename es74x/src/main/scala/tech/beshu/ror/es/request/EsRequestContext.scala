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

import java.time.Instant

import cats.data.NonEmptyList
import cats.implicits._
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.utils.RCUtils

import scala.collection.JavaConverters._
import scala.util.Try

class EsRequestContext private(channel: RestChannel,
                               override val taskId: Long,
                               actionType: String,
                               actionRequest: ActionRequest,
                               clusterService: RorClusterService,
                               threadPool: ThreadPool,
                               crossClusterSearchEnabled: Boolean)
  extends RequestContext with Logging {

  private val request = channel.request()

  override lazy val timestamp: Instant =
    Instant.now()

  override lazy val id: RequestContext.Id = RequestContext.Id(s"${request.hashCode()}-${actionRequest.hashCode()}#$taskId")

  override lazy val action: Action = Action(actionType)

  override lazy val headers: Set[Header] = {
    val (authorizationHeaders, otherHeaders) =
      request
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
    Try(request.getHttpChannel.getRemoteAddress.getAddress.getHostAddress)
      .toEither
      .left
      .map(ex => logger.error("Could not extract remote address", ex))
      .map { remoteHost => if (RCUtils.isLocalHost(remoteHost)) RCUtils.LOCALHOST else remoteHost }
      .toOption
      .flatMap(Address.from)

  override lazy val localAddress: Address =
    Try(request.getHttpChannel.getLocalAddress.getAddress.getHostAddress)
      .toEither
      .left
      .map(ex => logger.error("Could not extract local address", ex))
      .toOption
      .flatMap(Address.from)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname"))

  override lazy val method: Method = Method(request.method().name())

  override lazy val uriPath: UriPath = UriPath(request.path())

  override lazy val contentLength: Information = Bytes(if(request.content == null) 0 else request.content().length())

  override lazy val `type`: Type = Type(actionRequest.getClass.getSimpleName)

  override lazy val content: String = if(request.content == null) "" else request.content().utf8ToString()

  override lazy val operation: Operation = ???

  override lazy val indices: Set[domain.IndexName] = ???

  override lazy val allIndicesAndAliases: Set[IndexWithAliases] =
    clusterService
      .allIndicesAndAliases
      .flatMap { case (indexName, aliases) =>
        IndexName
          .fromString(indexName)
          .map { index =>
            IndexWithAliases(index, aliases.flatMap(IndexName.fromString))
          }
      }
      .toSet

  override lazy val templateIndicesPatterns: Set[IndexName] = ???

  override lazy val repositories: Set[IndexName] = ???

  override lazy val snapshots: Set[IndexName] = ???

  override lazy val allTemplates: Set[Template] = ???

  override lazy val isReadOnlyRequest: Boolean = ???

  override lazy val involvesIndices: Boolean = ???

  override lazy val isCompositeRequest: Boolean = ???

  override lazy val isAllowedForDLS: Boolean = {
    actionRequest match {
      case _ if !isReadOnlyRequest => false
      case sr: SearchRequest if sr.source() == null => true
      case sr: SearchRequest if sr.source.profile || (sr.source.suggest != null && !sr.source.suggest.getSuggestions.isEmpty) => false
      case _ => true
    }
  }

  override val hasRemoteClusters: Boolean = crossClusterSearchEnabled
}

object EsRequestContext {
  def from(channel: RestChannel,
           taskId: Long,
           action: String,
           actionRequest: ActionRequest,
           clusterService: RorClusterService,
           threadPool: ThreadPool,
           crossClusterSearchEnabled: Boolean): Try[RequestContext] =
    Try(new EsRequestContext(channel, taskId, action, actionRequest, clusterService, threadPool, crossClusterSearchEnabled))
}