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
package tech.beshu.ror.acl.request

import java.time.Instant

import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.domain
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.shims.request.RequestInfoShim

import scala.collection.JavaConverters._
import scala.util.Try

// fixme: maybe we don;'t need RequestInfoShim
class EsRequestContext private (rInfo: RequestInfoShim) extends RequestContext {

  override val timestamp: Instant =
    Instant.now()

  override val taskId: Long =
    rInfo.extractTaskId()

  override val id: RequestContext.Id =
  Option(rInfo.extractId)
    .map(RequestContext.Id.apply)
    .getOrElse(throw new IllegalArgumentException(s"Cannot create request ID"))

  override val action: Action =
    Option(rInfo.extractAction)
      .map(Action.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request action"))

  override val headers: Set[Header] =
    rInfo
      .extractRequestHeaders.asScala
      .flatMap { case (name, value) =>
        (NonEmptyString.unapply(name), NonEmptyString.unapply(value)) match {
          case (Some(headerName), Some(headerValue)) => Some(Header(Name(headerName), headerValue))
          case _ => None
        }
      }
      .toSet

  override val remoteAddress: Option[Address] =
    Try(rInfo.extractRemoteAddress()).toOption
      .flatMap(Address.from)

  override val localAddress: Address =
    forceCreateAddressFrom(rInfo.extractLocalAddress())

  override val method: Method =
    Option(rInfo.extractMethod)
      .map(Method.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request method"))

  override val uriPath: UriPath =
  Option(rInfo.extractURI)
    .map(UriPath.apply)
    .getOrElse(throw new IllegalArgumentException(s"Cannot create request URI path"))

  override val contentLength: Information =
    Bytes(rInfo.extractContentLength().toLong)

  override val `type`: Type =
    Option(rInfo.extractType)
      .map(Type.apply)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create request type"))

  override val content: String =
    Option(rInfo.extractContent()).getOrElse("")

  override val indices: Set[domain.IndexName] =
    rInfo.extractIndices().asScala.flatMap(IndexName.fromString).toSet

  override val allIndicesAndAliases: Set[IndexWithAliases] =
    rInfo
      .extractAllIndicesAndAliases().asScala
      .flatMap { entry =>
        IndexName
          .fromString(entry.getKey)
          .map { index =>
            IndexWithAliases(index, entry.getValue.asScala.flatMap(IndexName.fromString).toSet)
          }
      }
      .toSet

  override val templateIndicesPatterns: Set[IndexName] =
    rInfo.extractTemplateIndicesPatterns().asScala.flatMap(IndexName.fromString).toSet

  override val repositories: Set[IndexName] =
    rInfo.extractRepositories().asScala.flatMap(IndexName.fromString).toSet

  override val snapshots: Set[IndexName] =
    rInfo.extractSnapshots().asScala.flatMap(IndexName.fromString).toSet

  override val isReadOnlyRequest: Boolean =
    rInfo.extractIsReadRequest()

  override val involvesIndices: Boolean =
    rInfo.involvesIndices()

  override val isCompositeRequest: Boolean =
    rInfo.extractIsCompositeRequest()

  override val isAllowedForDLS: Boolean =
    rInfo.extractIsAllowedForDLS()

  override val hasRemoteClusters: Boolean =
    rInfo.extractHasRemoteClusters()

  private def forceCreateAddressFrom(value: String) = {
    Address.from(value).getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname from $value"))
  }
}

object EsRequestContext {
  def from(rInfo: RequestInfoShim): Try[RequestContext] = Try(new EsRequestContext(rInfo))
}