package tech.beshu.ror.es.request.context

import java.time.Instant

import cats.data.NonEmptyList
import cats.implicits._
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.{CompositeIndicesRequest, IndicesRequest}
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.utils.RCUtils

import scala.collection.JavaConverters._
import scala.util.Try

abstract class BaseEsRequestContext[B <: BlockContext](esContext: EsContext,
                                                       clusterService: RorClusterService)
  extends RequestContext with Logging {

  override type BLOCK_CONTEXT = B

  private val restRequest = esContext.channel.request()

  override lazy val timestamp: Instant =
    Instant.now()

  override val taskId: Long = esContext.task.getId

  override lazy val id: RequestContext.Id = RequestContext.Id(s"${restRequest.hashCode()}-${esContext.actionRequest.hashCode()}#$taskId")

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
    Try(restRequest.getHttpChannel.getRemoteAddress.getAddress.getHostAddress)
      .toEither
      .left
      .map(ex => logger.error("Could not extract remote address", ex))
      .map { remoteHost => if (RCUtils.isLocalHost(remoteHost)) RCUtils.LOCALHOST else remoteHost }
      .toOption
      .flatMap(Address.from)

  override lazy val localAddress: Address =
    Try(restRequest.getHttpChannel.getLocalAddress.getAddress.getHostAddress)
      .toEither
      .left
      .map(ex => logger.error("Could not extract local address", ex))
      .toOption
      .flatMap(Address.from)
      .getOrElse(throw new IllegalArgumentException(s"Cannot create IP or hostname"))

  override lazy val method: Method = Method(restRequest.method().name())

  override lazy val uriPath: UriPath = UriPath(restRequest.path())

  override lazy val contentLength: Information = Bytes(if (restRequest.content == null) 0 else restRequest.content().length())

  override lazy val `type`: Type = Type(esContext.actionRequest.getClass.getSimpleName)

  override lazy val content: String = if (restRequest.content == null) "" else restRequest.content().utf8ToString()

  override lazy val allIndicesAndAliases: Set[IndexWithAliases] =
    clusterService
      .allIndicesAndAliases
      .map { case (indexName, aliases) => IndexWithAliases(indexName, aliases) }
      .toSet

  // todo: to remove
  override lazy val templateIndicesPatterns: Set[IndexName] = Set.empty

  override lazy val isReadOnlyRequest: Boolean = RCUtils.isReadRequest(action.value)

  // todo; this info is in block context
  override lazy val involvesIndices: Boolean = {
    val actionRequest = esContext.actionRequest
    actionRequest.isInstanceOf[IndicesRequest] || actionRequest.isInstanceOf[CompositeIndicesRequest] ||
      // Necessary because it won't implement IndicesRequest as it should (bug: https://github.com/elastic/elasticsearch/issues/28671)
      actionRequest.isInstanceOf[RestoreSnapshotRequest] ||
      actionRequest.isInstanceOf[GetIndexTemplatesRequest] || actionRequest.isInstanceOf[PutIndexTemplateRequest] || actionRequest.isInstanceOf[DeleteIndexTemplateRequest]
  }

  override lazy val isCompositeRequest: Boolean = esContext.actionRequest.isInstanceOf[CompositeIndicesRequest]

  override lazy val isAllowedForDLS: Boolean = {
    esContext.actionRequest match {
      case _ if !isReadOnlyRequest => false
      case sr: SearchRequest if sr.source() == null => true
      case sr: SearchRequest if sr.source.profile || (sr.source.suggest != null && !sr.source.suggest.getSuggestions.isEmpty) => false
      case _ => true
    }
  }

  override val hasRemoteClusters: Boolean = esContext.crossClusterSearchEnabled
}