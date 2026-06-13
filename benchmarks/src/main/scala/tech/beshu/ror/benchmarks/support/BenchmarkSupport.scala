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
package tech.beshu.ror.benchmarks.support

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import squants.information.Bytes
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralIndexRequestBlockContext, GeneralNonIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.es.services.EsClusterService
import tech.beshu.ror.es.services.EsClusterService.{Document, DocumentsAccessibility, IndexOrAlias, IndexUuid}
import tech.beshu.ror.syntax.*

import java.time.Instant

/**
 * Shared request scaffolding for the KPI benchmarks. Only production types are used here,
 * so every benchmark measures the real per-request entry points of `core`.
 */
object BenchmarkSupport {

  def nes(value: String): NonEmptyString = NonEmptyString.unsafeFrom(value)

  val searchAction: Action = Action("indices:data/read/search")

  // The single-rule benchmarks call `initialBlockContext(noBlock)` because the owning `Block` is
  // never dereferenced on the measured path — `rule.check` reads only the block context, while
  // `blockContext.block` is touched solely by audit/logging. Building a real `Block` here would need
  // a NonEmptyList[Rule] + an implicit LoggingContext for a field that is never read. Routed through
  // this named constant so the intent is explicit and grep-able: if a future rule-eval change starts
  // reading `blockContext.block`, all of these sites NPE together and this is where to fix it.
  val noBlock: Block = null

  // Realistic request header shape: 18 filler headers + one custom header + basic-auth credentials.
  def realisticHeaders(credentials: Credentials): Set[Header] =
    (1 to 18).map(i => Header(Header.Name(nes(s"X-Filler-$i")), nes(s"value-$i"))).toCovariantSet +
      Header(Header.Name(nes("X-Custom-1")), nes("value-1")) +
      BasicAuth.fromCredentials(credentials).header

  final class BenchRestRequest(override val allHeaders: Set[Header]) extends RestRequest {
    override val method: Method = Method.GET
    override val path: UriPath = UriPath.from("/idx/_search").get
    override val localAddress: Address = Address.from("127.0.0.1").get
    override val remoteAddress: Option[Address] = Address.from("127.0.0.1")
    override val content: String = ""
    override val contentLength: squants.information.Information = Bytes(0)
  }

  sealed abstract class BaseBenchRequestContext(headers: Set[Header],
                                                override val action: Action) extends RequestContext {
    override val restRequest: RestRequest = new BenchRestRequest(headers)
    // Fixed sentinels, not Instant.now()/CorrelationId.random: neither is read on the ACL hot
    // path (only in audit/metadata), and CorrelationId.random draws from SecureRandom — which on
    // BasicAuthDecodeBenchmark (the one context built inside @Benchmark) would otherwise add UUID
    // generation cost to every measured iteration and masquerade as a basic-auth regression.
    override val timestamp: Instant = Instant.EPOCH
    override val taskId: Long = 0L
    override val id: RequestContext.Id = RequestContext.Id.fromString("benchmark")
    override val rorKibanaSessionId: CorrelationId = CorrelationId(nes("benchmark-session-id"))
    override val `type`: Type = Type("SearchRequest")
    override val requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
    override val indexAttributes: IndexAttributeFilter = IndexAttributeFilter.All
    override val esServices: EsServices = emptyEsServices
    override val isCompositeRequest: Boolean = false
    override val isAllowedForDLS: Boolean = true
  }

  final class NonIndexRequestContext(headers: Set[Header],
                                     action: Action = searchAction)
    extends BaseBenchRequestContext(headers, action) {
    override type BLOCK_CONTEXT = GeneralNonIndexRequestBlockContext

    override def initialBlockContext(block: Block): GeneralNonIndexRequestBlockContext =
      GeneralNonIndexRequestBlockContext(block, this, BlockMetadata.from(this), Set.empty, List.empty)
  }

  final class IndexRequestContext(headers: Set[Header],
                                  requested: Set[RequestedIndex[ClusterIndexName]])
    extends BaseBenchRequestContext(headers, searchAction) {
    override type BLOCK_CONTEXT = GeneralIndexRequestBlockContext
    override val requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = Some(requested)

    override def initialBlockContext(block: Block): GeneralIndexRequestBlockContext =
      GeneralIndexRequestBlockContext(block, this, BlockMetadata.from(this), Set.empty, List.empty, requested, Set.empty, Set.empty)
  }

  // Empty-cluster stub: authorizing concrete (non-wildcard) index names never expands these lists.
  // The two nulls are serviceAccountTokenService and apiKeyService — not invoked by any rule on the
  // measured ACL path (only the auth_account_token / api_key rules touch them, none of which the KPI
  // benchmarks exercise), so they are left unstubbed rather than carrying empty fakes.
  lazy val emptyEsServices: EsServices = new EsServices(emptyClusterService, null, null)

  private lazy val emptyClusterService: EsClusterService = new EsClusterService {
    override def remoteClustersConfigured(implicit id: RequestId): Boolean = false
    override def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full] = Set.empty
    override def indexOrAliasUuids(indexOrAlias: IndexOrAlias)(implicit id: RequestId): Set[IndexUuid] = Set.empty
    override def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty)
    override def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty)
    override def legacyTemplates(implicit id: RequestId): Set[Template.LegacyTemplate] = Set.empty
    override def indexTemplates(implicit id: RequestId): Set[Template.IndexTemplate] = Set.empty
    override def componentTemplates(implicit id: RequestId): Set[Template.ComponentTemplate] = Set.empty
    override def allSnapshots(implicit id: RequestId): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] = Map.empty
    override def snapshotIndices(repositoryName: RepositoryName.Full, snapshotName: SnapshotName.Full)
                                (implicit id: RequestId): Task[Set[ClusterIndexName]] = Task.now(Set.empty)
    override def verifyDocumentAccessibility(document: Document, filter: Filter)
                                            (implicit id: RequestId): Task[DocumentAccessibility] =
      Task.raiseError(new UnsupportedOperationException("not used by benchmarks"))
    override def verifyDocumentsAccessibility(documents: NonEmptyList[Document], filter: Filter)
                                             (implicit id: RequestId): Task[DocumentsAccessibility] =
      Task.raiseError(new UnsupportedOperationException("not used by benchmarks"))
    override def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases] = Set.empty
    override def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases] = Set.empty
  }
}
