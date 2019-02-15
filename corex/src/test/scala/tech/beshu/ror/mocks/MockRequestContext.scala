package tech.beshu.ror.mocks

import java.time.Instant

import com.softwaremill.sttp.Method
import squants.information.{Bytes, Information}
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.request.RequestContext

final case class MockRequestContext(override val timestamp: Instant = Instant.now(),
                                    override val taskId: Long = 0L,
                                    override val id: RequestContext.Id = RequestContext.Id("mock"),
                                    override val `type`: Type = Type("default-type"),
                                    override val action: Action = Action("default-action"),
                                    override val headers: Set[Header] = Set.empty,
                                    override val remoteAddress: Address = Address("default-remote-addr"),
                                    override val localAddress: Address = Address("default-local-addr"),
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
