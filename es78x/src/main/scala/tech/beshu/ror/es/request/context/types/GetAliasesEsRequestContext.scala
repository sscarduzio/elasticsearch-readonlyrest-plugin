package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.AliasRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

class GetAliasesEsRequestContext(actionRequest: GetAliasesRequest,
                                 esContext: EsContext,
                                 aclContext: AccessControlStaticContext,
                                 clusterService: RorClusterService,
                                 override val threadPool: ThreadPool)
  extends BaseEsRequestContext[AliasRequestBlockContext](esContext, clusterService)
    with EsRequest[AliasRequestBlockContext] {

  override val initialBlockContext: AliasRequestBlockContext = AliasRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    {
      val indices = aliasesFrom(actionRequest)
      logger.debug(s"[${id.show}] Discovered aliases: ${indices.map(_.show).mkString(",")}")
      indices
    },
    {
      val indices = indicesFrom(actionRequest)
      logger.debug(s"[${id.show}] Discovered indices: ${indices.map(_.show).mkString(",")}")
      indices
    },
  )

  override protected def modifyRequest(blockContext: AliasRequestBlockContext): ModificationResult = {
    val result = for {
      indices <- NonEmptyList.fromList(blockContext.indices.toList)
      aliases <- NonEmptyList.fromList(blockContext.aliases.toList)
    } yield (indices, aliases)
    result match {
      case Some((indices, aliases)) =>
        actionRequest.indices(indices.map(_.value.value).toList: _*)
        actionRequest.aliases(aliases.map(_.value.value).toList: _*)
        Modified
      case None =>
        logger.error(s"[${id.show}] At least one alias and one index has to be allowed. " +
          s"Found allowed indices: [${blockContext.indices.map(_.show).mkString(",")}]." +
          s"Found allowed aliases: [${blockContext.aliases.map(_.show).mkString(",")}]")
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: GetAliasesRequest) = {
    indicesOrWildcard(request.indices().asSafeSet.flatMap(IndexName.fromString))
  }

  private def aliasesFrom(request: GetAliasesRequest) = {
    indicesOrWildcard(request.aliases().asSafeSet.flatMap(IndexName.fromString))
  }
}
