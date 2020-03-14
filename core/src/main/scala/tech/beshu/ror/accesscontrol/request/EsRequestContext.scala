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
package tech.beshu.ror.accesscontrol.request

import java.time.Instant

import cats.implicits._
import cats.data.NonEmptyList
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.show.logs.authorizationValueErrorShow

import scala.util.Try

class EsRequestContext private(rInfo: RequestInfoShim) extends RequestContext {

  override val timestamp: Instant =
    Instant.now()

  override lazy val taskId: Long =
    rInfo.extractTaskId

  override lazy val id: RequestContext.Id =
    Option(rInfo.extractId)
      .map(RequestContext.Id.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request ID"))

  override lazy val action: Action =
    Option(rInfo.extractAction)
      .map(Action.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request action"))

  override lazy val headers: Set[Header] = {
    val (authorizationHeaders, otherHeaders) = rInfo
      .extractRequestHeaders
      .flatMap { case (name, values) =>
        for {
          nonEmptyName <- NonEmptyString.unapply(name)
          nonEmptyValues <- NonEmptyList.fromList(values.toList.flatMap(NonEmptyString.unapply))
        } yield (Header.Name(nonEmptyName), nonEmptyValues)
      }
      .toSeq
      .partition { case (name, _) => name == Header.Name.authorization }
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
    Try(rInfo.extractRemoteAddress).toOption
      .flatMap(Address.from)

  override lazy val localAddress: Address =
    forceCreateAddressFrom(rInfo.extractLocalAddress)

  override lazy val method: Method =
    Option(rInfo.extractMethod)
      .map(Method.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request method"))

  override lazy val uriPath: UriPath =
    Option(rInfo.extractPath)
      .map(UriPath.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request URI path"))

  override lazy val contentLength: Information =
    Bytes(rInfo.extractContentLength.toLong)

  override lazy val `type`: Type =
    Option(rInfo.extractType)
      .map(Type.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request type"))

  override lazy val content: String =
    Option(rInfo.extractContent).getOrElse("")

  override lazy val indices: Set[domain.IndexName] =
    rInfo.extractIndices.indices.flatMap(IndexName.fromString)

  override lazy val allIndicesAndAliases: Set[IndexWithAliases] =
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

  override lazy val templateIndicesPatterns: Set[IndexName] =
    rInfo.extractTemplateIndicesPatterns.flatMap(IndexName.fromString)

  override lazy val repositories: Set[IndexName] =
    rInfo.extractRepositories.flatMap(IndexName.fromString)

  override lazy val snapshots: Set[IndexName] =
    rInfo.extractSnapshots.flatMap(IndexName.fromString)

  override lazy val isReadOnlyRequest: Boolean =
    rInfo.extractIsReadRequest

  override lazy val involvesIndices: Boolean =
    rInfo.involvesIndices

  override lazy val isCompositeRequest: Boolean =
    rInfo.extractIsCompositeRequest

  override lazy val isAllowedForDLS: Boolean =
    rInfo.extractIsAllowedForDLS

  override lazy val hasRemoteClusters: Boolean =
    rInfo.extractHasRemoteClusters

  private def forceCreateAddressFrom(value: String) = {
    Address.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname from $value"))
  }
}

object EsRequestContext {
  def from(rInfo: RequestInfoShim): Try[RequestContext] = Try(new EsRequestContext(rInfo))
}