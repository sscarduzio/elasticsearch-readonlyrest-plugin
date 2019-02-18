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
package tech.beshu.ror.mocks

import java.time.Instant

import com.softwaremill.sttp.Method
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.request.RequestContext

final case class MockRequestContext(override val timestamp: Instant = Instant.now(),
                                    override val taskId: Long = 0L,
                                    override val id: RequestContext.Id = RequestContext.Id("mock"),
                                    override val `type`: Type = Type("default-type"),
                                    override val action: Action = Action("default-action"),
                                    override val headers: Set[Header] = Set.empty,
                                    override val remoteAddress: Address = Address.from("localhost").get,
                                    override val localAddress: Address = Address.from("localhost").get,
                                    override val method: Method = Method("GET"),
                                    override val uriPath: UriPath = UriPath.restMetadataPath,
                                    override val contentLength: Information = Bytes(0),
                                    override val content: String = "",
                                    override val indices: Set[IndexName] = Set.empty,
                                    override val allIndicesAndAliases: Set[IndexName] = Set.empty,
                                    override val repositories: Set[IndexName] = Set.empty,
                                    override val snapshots: Set[IndexName] = Set.empty,
                                    override val involvesIndices: Boolean = false,
                                    override val isCompositeRequest: Boolean = false,
                                    override val isReadOnlyRequest: Boolean = true,
                                    override val isAllowedForDLS: Boolean = true)
  extends RequestContext

object MockRequestContext {
  def default: MockRequestContext = MockRequestContext()
}
