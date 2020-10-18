package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.{ResolvedAlias, ResolvedIndex}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class ResolveIndexEsRequestContext(actionRequest: ResolveIndexAction.Request,
                                   esContext: EsContext,
                                   aclContext: AccessControlStaticContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ResolveIndexAction.Request](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ResolveIndexAction.Request): Set[domain.IndexName] = indicesOrWildcard {
    request.indices().asSafeList.flatMap(IndexName.fromString).toSet
  }

  override protected def update(request: ResolveIndexAction.Request,
                                indices: NonEmptyList[domain.IndexName]): ModificationResult = {
    request.indices(indices.toList.map(_.value.value): _*)
    ModificationResult.UpdateResponse(filterResponse(_, indices))
  }

  private def filterResponse(response: ActionResponse, indices: NonEmptyList[IndexName]): Task[ActionResponse] = {
    response match {
      case r: ResolveIndexAction.Response => Task.now {
        doPrivileged { // todo: remove
          new ResolveIndexAction.Response(
            r.getIndices.asSafeList.flatMap(secureResolvedIndex(_, indices)).asJava,
            r.getAliases.asSafeList.flatMap(secureResolvedAlias(_, indices)).asJava,
            r.getDataStreams
          )
        }
      }
      case r => Task.now(r)
    }
  }

  private def secureResolvedIndex(resolvedIndex: ResolvedIndex, allowedIndices: NonEmptyList[IndexName]) = {
    if (isAllowed(resolvedIndex.getName, allowedIndices)) {
      val allowedResolvedAliases = resolvedIndex
        .getAliases.asSafeList
        .filter(isAllowed(_, allowedIndices))
      Some(createResolvedIndex(
        resolvedIndex.getName,
        allowedResolvedAliases,
        resolvedIndex.getAttributes,
        resolvedIndex.getDataStream
      ))
    } else {
      None
    }
  }

  private def secureResolvedAlias(resolvedAlias: ResolvedAlias, allowedIndices: NonEmptyList[IndexName]) = {
    if (isAllowed(resolvedAlias.getName, allowedIndices)) {
      val allowedResolvedIndices = resolvedAlias
        .getIndices.asSafeList
        .filter(isAllowed(_, allowedIndices))
      Some(createResolvedAlias(
        resolvedAlias.getName,
        allowedResolvedIndices
      ))
    } else {
      None
    }
  }

  private def isAllowed(aliasOrIndex: String, allowedIndices: NonEmptyList[IndexName]) = {
    val resolvedAliasOrIndexName = IndexName.fromUnsafeString(aliasOrIndex)
    allowedIndices.exists(_.matches(resolvedAliasOrIndexName))
  }

  private def createResolvedIndex(index: String, aliases: List[String], attributes: Array[String], datastream: String) = {
    onClass(classOf[ResolvedIndex])
      .create(index, aliases.toArray, attributes, datastream)
      .get[ResolvedIndex]()
  }

  private def createResolvedAlias(alias: String, indices: List[String]) = {
    onClass(classOf[ResolvedAlias])
      .create(alias, indices.toArray)
      .get[ResolvedAlias]()
  }
}
