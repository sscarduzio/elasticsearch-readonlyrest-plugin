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
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService

import scala.collection.JavaConverters._
import scala.util.Try

class EsRequestContext private(channel: RestChannel,
                               override val taskId: Long,
                               actionType: String,
                               actionRequest: ActionRequest,
                               clusterService: RorClusterService,
                               threadPool: ThreadPool,
                               crossClusterSearchEnabled: Boolean)
  extends RequestContext {

  private val request = channel.request()

  override val timestamp: Instant =
    Instant.now()

  override val id: RequestContext.Id = RequestContext.Id(s"${request.hashCode()}-${actionRequest.hashCode()}#$taskId")

  override val action: Action = Action(actionType)

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

  override val remoteAddress: Option[Address] =
    Try(rInfo.extractRemoteAddress).toOption
      .flatMap(Address.from)

  override val localAddress: Address =
    forceCreateAddressFrom(rInfo.extractLocalAddress)

  override val method: Method =
    Option(rInfo.extractMethod)
      .map(Method.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request method"))

  override val uriPath: UriPath =
    Option(rInfo.extractPath)
      .map(UriPath.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request URI path"))

  override val contentLength: Information =
    Bytes(rInfo.extractContentLength.toLong)

  override val `type`: Type =
    Option(rInfo.extractType)
      .map(Type.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request type"))

  override val content: String =
    Option(rInfo.extractContent).getOrElse("")

  override val indicesOperation: InvolvingIndexOperation =
    rInfo.indicesOperation

  override val indices: Set[domain.IndexName] =
    rInfo.extractIndices.indices.flatMap(IndexName.fromString)

  override val allIndicesAndAliases: Set[IndexWithAliases] =
    rInfo
      .extractAllIndicesAndAliases
      .flatMap { case (indexName, aliases) =>
        IndexName
          .fromString(indexName)
          .map { index =>
            IndexWithAliases(index, aliases.flatMap(IndexName.fromString))
          }
      }
      .toSet

  override val templateIndicesPatterns: Set[IndexName] =
    rInfo.extractTemplateIndicesPatterns.flatMap(IndexName.fromString)

  override val repositories: Set[IndexName] =
    rInfo.extractRepositories.flatMap(IndexName.fromString)

  override val snapshots: Set[IndexName] =
    rInfo.extractSnapshots.flatMap(IndexName.fromString)

  override val isReadOnlyRequest: Boolean =
    rInfo.extractIsReadRequest

  override val involvesIndices: Boolean =
    rInfo.involvesIndices

  override val isCompositeRequest: Boolean =
    rInfo.extractIsCompositeRequest

  override val isAllowedForDLS: Boolean =
    rInfo.extractIsAllowedForDLS

  override val hasRemoteClusters: Boolean =
    rInfo.extractHasRemoteClusters

  private def forceCreateAddressFrom(value: String) = {
    Address.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname from $value"))
  }
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