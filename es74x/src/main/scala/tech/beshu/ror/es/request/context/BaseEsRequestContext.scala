package tech.beshu.ror.es.request.context

import java.time.Instant

import cats.data.NonEmptyList
import cats.implicits._
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
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
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.Operation.AnIndexOperation
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.utils.RCUtils
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._
import scala.util.Try

abstract class BaseEsRequestContext[B <: BlockContext.Aux[B, O], O <: Operation](channel: RestChannel,
                                                                                 override val taskId: Long,
                                                                                 actionType: String,
                                                                                 val actionRequest: ActionRequest,
                                                                                 clusterService: RorClusterService,
                                                                                 threadPool: ThreadPool,
                                                                                 crossClusterSearchEnabled: Boolean)
  extends RequestContext[O] with Logging {

  override type BLOCK_CONTEXT = B

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

  override lazy val contentLength: Information = Bytes(if (request.content == null) 0 else request.content().length())

  override lazy val `type`: Type = Type(actionRequest.getClass.getSimpleName)

  override lazy val content: String = if (request.content == null) "" else request.content().utf8ToString()

  // todo: to remove
  override lazy val __old_indices: Set[domain.IndexName] = Set.empty

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

  // todo: to remove
  override lazy val templateIndicesPatterns: Set[IndexName] = Set.empty

  override lazy val repositories: Set[IndexName] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.repository.asSafeSet.flatMap(IndexName.fromString)
      case ar: CreateSnapshotRequest => ar.repository.asSafeSet.flatMap(IndexName.fromString)
      case ar: DeleteSnapshotRequest => ar.repository.asSafeSet.flatMap(IndexName.fromString)
      case ar: RestoreSnapshotRequest => ar.repository.asSafeSet.flatMap(IndexName.fromString)
      case ar: SnapshotsStatusRequest => ar.repository.asSafeSet.flatMap(IndexName.fromString)
      case ar: PutRepositoryRequest => ar.name.asSafeSet.flatMap(IndexName.fromString)
      case ar: GetRepositoriesRequest => ar.repositories.asSafeSet.flatMap(IndexName.fromString)
      case ar: DeleteRepositoryRequest => ar.name.asSafeSet.flatMap(IndexName.fromString)
      case ar: VerifyRepositoryRequest => ar.name.asSafeSet.flatMap(IndexName.fromString)
      case _ => Set.empty[IndexName]
    }
  }

  override lazy val snapshots: Set[IndexName] = {
    actionRequest match {
      case ar: GetSnapshotsRequest => ar.snapshots.asSafeSet.flatMap(IndexName.fromString)
      case ar: CreateSnapshotRequest => ar.snapshot.asSafeSet.flatMap(IndexName.fromString)
      case ar: DeleteSnapshotRequest => ar.snapshot.asSafeSet.flatMap(IndexName.fromString)
      case ar: RestoreSnapshotRequest => ar.snapshot.asSafeSet.flatMap(IndexName.fromString)
      case ar: SnapshotsStatusRequest => ar.snapshots.asSafeSet.flatMap(IndexName.fromString)
      case _ => Set.empty[IndexName]
    }
  }

  // todo: implement
  override lazy val allTemplates: Set[Template] = ???

  override lazy val isReadOnlyRequest: Boolean = RCUtils.isReadRequest(action.value)

  override lazy val involvesIndices: Boolean = operation.isInstanceOf[AnIndexOperation]

  override lazy val isCompositeRequest: Boolean = actionRequest.isInstanceOf[CompositeIndicesRequest]

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