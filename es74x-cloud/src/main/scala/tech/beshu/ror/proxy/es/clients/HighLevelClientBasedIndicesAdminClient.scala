/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import monix.execution.Scheduler
import org.elasticsearch.action._
import org.elasticsearch.action.admin.indices.alias.exists.{AliasesExistRequestBuilder, AliasesExistResponse}
import org.elasticsearch.action.admin.indices.alias.get.{GetAliasesAction, GetAliasesRequest, GetAliasesRequestBuilder, GetAliasesResponse}
import org.elasticsearch.action.admin.indices.alias.{IndicesAliasesAction, IndicesAliasesRequest, IndicesAliasesRequestBuilder}
import org.elasticsearch.action.admin.indices.analyze.{AnalyzeAction, AnalyzeRequestBuilder}
import org.elasticsearch.action.admin.indices.cache.clear.{ClearIndicesCacheAction, ClearIndicesCacheRequest, ClearIndicesCacheRequestBuilder, ClearIndicesCacheResponse}
import org.elasticsearch.action.admin.indices.close.{CloseIndexAction, CloseIndexRequest, CloseIndexRequestBuilder, CloseIndexResponse}
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexAction, DeleteIndexRequest, DeleteIndexRequestBuilder}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsAction, IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.admin.indices.exists.types.{TypesExistsRequest, TypesExistsRequestBuilder, TypesExistsResponse}
import org.elasticsearch.action.admin.indices.flush._
import org.elasticsearch.action.admin.indices.forcemerge.{ForceMergeAction, ForceMergeRequest, ForceMergeRequestBuilder, ForceMergeResponse}
import org.elasticsearch.action.admin.indices.get.{GetIndexAction, GetIndexRequest, GetIndexRequestBuilder, GetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get._
import org.elasticsearch.action.admin.indices.mapping.put.{PutMappingAction, PutMappingRequest, PutMappingRequestBuilder}
import org.elasticsearch.action.admin.indices.open.{OpenIndexAction, OpenIndexRequest, OpenIndexRequestBuilder, OpenIndexResponse}
import org.elasticsearch.action.admin.indices.recovery.{RecoveryRequest, RecoveryRequestBuilder, RecoveryResponse}
import org.elasticsearch.action.admin.indices.refresh.{RefreshAction, RefreshRequest, RefreshRequestBuilder, RefreshResponse}
import org.elasticsearch.action.admin.indices.rollover.{RolloverAction, RolloverRequest, RolloverRequestBuilder, RolloverResponse}
import org.elasticsearch.action.admin.indices.segments.{IndicesSegmentResponse, IndicesSegmentsRequest, IndicesSegmentsRequestBuilder}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsAction, GetSettingsRequest, GetSettingsRequestBuilder, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.settings.put.{UpdateSettingsAction, UpdateSettingsRequest, UpdateSettingsRequestBuilder}
import org.elasticsearch.action.admin.indices.shards.{IndicesShardStoreRequestBuilder, IndicesShardStoresRequest, IndicesShardStoresResponse}
import org.elasticsearch.action.admin.indices.shrink.{ResizeAction, ResizeRequest, ResizeRequestBuilder, ResizeResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsAction, IndicesStatsRequest, IndicesStatsRequestBuilder, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.delete.{DeleteIndexTemplateAction, DeleteIndexTemplateRequest, DeleteIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesAction, GetIndexTemplatesRequest, GetIndexTemplatesRequestBuilder, GetIndexTemplatesResponse}
import org.elasticsearch.action.admin.indices.template.put.{PutIndexTemplateAction, PutIndexTemplateRequest, PutIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.upgrade.get.{UpgradeStatusRequest, UpgradeStatusRequestBuilder, UpgradeStatusResponse}
import org.elasticsearch.action.admin.indices.upgrade.post.{UpgradeRequest, UpgradeRequestBuilder, UpgradeResponse}
import org.elasticsearch.action.admin.indices.validate.query.{ValidateQueryAction, ValidateQueryRequest, ValidateQueryRequestBuilder, ValidateQueryResponse}
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.IndicesAdminClient
import org.elasticsearch.cluster.metadata.AliasMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.proxy.es.ProxyIndexLevelActionFilter
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy

import scala.collection.JavaConverters._

class HighLevelClientBasedIndicesAdminClient(esClient: RestHighLevelClientAdapter,
                                             override val proxyFilter: ProxyIndexLevelActionFilter)
                                            (implicit override val scheduler: Scheduler)
  extends IndicesAdminClient with ProxyFilterable {

  override def exists(request: IndicesExistsRequest): ActionFuture[IndicesExistsResponse] = throw NotDefinedForRorProxy

  override def exists(request: IndicesExistsRequest, listener: ActionListener[IndicesExistsResponse]): Unit = {
    execute(IndicesExistsAction.INSTANCE.name(), request, listener) {
      esClient.indicesExists
    }
  }

  override def prepareExists(indices: String*): IndicesExistsRequestBuilder = throw NotDefinedForRorProxy

  override def typesExists(request: TypesExistsRequest): ActionFuture[TypesExistsResponse] = throw NotDefinedForRorProxy

  override def typesExists(request: TypesExistsRequest, listener: ActionListener[TypesExistsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareTypesExists(index: String*): TypesExistsRequestBuilder = throw NotDefinedForRorProxy

  override def stats(request: IndicesStatsRequest): ActionFuture[IndicesStatsResponse] = throw NotDefinedForRorProxy

  override def stats(request: IndicesStatsRequest, listener: ActionListener[IndicesStatsResponse]): Unit = {
    execute(IndicesStatsAction.INSTANCE.name(), request, listener) {
      esClient.stats
    }
  }

  override def prepareStats(indices: String*): IndicesStatsRequestBuilder = throw NotDefinedForRorProxy

  override def recoveries(request: RecoveryRequest): ActionFuture[RecoveryResponse] = throw NotDefinedForRorProxy

  override def recoveries(request: RecoveryRequest, listener: ActionListener[RecoveryResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareRecoveries(indices: String*): RecoveryRequestBuilder = throw NotDefinedForRorProxy

  override def segments(request: IndicesSegmentsRequest): ActionFuture[IndicesSegmentResponse] = throw NotDefinedForRorProxy

  override def segments(request: IndicesSegmentsRequest, listener: ActionListener[IndicesSegmentResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareSegments(indices: String*): IndicesSegmentsRequestBuilder = throw NotDefinedForRorProxy

  override def shardStores(request: IndicesShardStoresRequest): ActionFuture[IndicesShardStoresResponse] = throw NotDefinedForRorProxy

  override def shardStores(request: IndicesShardStoresRequest, listener: ActionListener[IndicesShardStoresResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareShardStores(indices: String*): IndicesShardStoreRequestBuilder = throw NotDefinedForRorProxy

  override def create(request: CreateIndexRequest): ActionFuture[CreateIndexResponse] = throw NotDefinedForRorProxy

  override def create(request: CreateIndexRequest, listener: ActionListener[CreateIndexResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareCreate(index: String): CreateIndexRequestBuilder = throw NotDefinedForRorProxy

  override def delete(request: DeleteIndexRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def delete(request: DeleteIndexRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(DeleteIndexAction.INSTANCE.name(), request, listener) {
      esClient.deleteIndex
    }
  }

  override def prepareDelete(indices: String*): DeleteIndexRequestBuilder = throw NotDefinedForRorProxy

  override def close(request: CloseIndexRequest): ActionFuture[CloseIndexResponse] = throw NotDefinedForRorProxy

  override def close(request: CloseIndexRequest, listener: ActionListener[CloseIndexResponse]): Unit = {
    execute(CloseIndexAction.INSTANCE.name(), request, listener) {
      esClient.closeIndex
    }
  }

  override def prepareClose(indices: String*): CloseIndexRequestBuilder = throw NotDefinedForRorProxy

  override def open(request: OpenIndexRequest): ActionFuture[OpenIndexResponse] = throw NotDefinedForRorProxy

  override def open(request: OpenIndexRequest, listener: ActionListener[OpenIndexResponse]): Unit = {
    execute(OpenIndexAction.INSTANCE.name(), request, listener) {
      esClient.openIndex
    }
  }

  override def prepareOpen(indices: String*): OpenIndexRequestBuilder = throw NotDefinedForRorProxy

  override def refresh(request: RefreshRequest): ActionFuture[RefreshResponse] = throw NotDefinedForRorProxy

  override def refresh(request: RefreshRequest, listener: ActionListener[RefreshResponse]): Unit = {
    execute(RefreshAction.INSTANCE.name(), request, listener) {
      esClient.refresh
    }
  }

  override def prepareRefresh(indices: String*): RefreshRequestBuilder = throw NotDefinedForRorProxy

  override def flush(request: FlushRequest): ActionFuture[FlushResponse] = throw NotDefinedForRorProxy

  override def flush(request: FlushRequest, listener: ActionListener[FlushResponse]): Unit = {
    execute(FlushAction.INSTANCE.name(), request, listener) {
      esClient.flush
    }
  }

  override def prepareFlush(indices: String*): FlushRequestBuilder = throw NotDefinedForRorProxy

  override def syncedFlush(request: SyncedFlushRequest): ActionFuture[SyncedFlushResponse] = throw NotDefinedForRorProxy

  override def syncedFlush(request: SyncedFlushRequest, listener: ActionListener[SyncedFlushResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareSyncedFlush(indices: String*): SyncedFlushRequestBuilder = throw NotDefinedForRorProxy

  override def forceMerge(request: ForceMergeRequest): ActionFuture[ForceMergeResponse] = throw NotDefinedForRorProxy

  override def forceMerge(request: ForceMergeRequest, listener: ActionListener[ForceMergeResponse]): Unit = {
    execute(ForceMergeAction.INSTANCE.name(), request, listener) {
      esClient.forceMerge
    }
  }

  override def prepareForceMerge(indices: String*): ForceMergeRequestBuilder = throw NotDefinedForRorProxy

  override def upgrade(request: UpgradeRequest): ActionFuture[UpgradeResponse] = throw NotDefinedForRorProxy

  override def upgrade(request: UpgradeRequest, listener: ActionListener[UpgradeResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpgradeStatus(indices: String*): UpgradeStatusRequestBuilder = throw NotDefinedForRorProxy

  override def upgradeStatus(request: UpgradeStatusRequest): ActionFuture[UpgradeStatusResponse] = throw NotDefinedForRorProxy

  override def upgradeStatus(request: UpgradeStatusRequest, listener: ActionListener[UpgradeStatusResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpgrade(indices: String*): UpgradeRequestBuilder = throw NotDefinedForRorProxy

  override def getMappings(request: GetMappingsRequest, listener: ActionListener[GetMappingsResponse]): Unit = {
    execute(GetMappingsAction.INSTANCE.name(), request, listener) {
      esClient.getMappings
    }
  }

  override def getMappings(request: GetMappingsRequest): ActionFuture[GetMappingsResponse] = throw NotDefinedForRorProxy

  override def prepareGetMappings(indices: String*): GetMappingsRequestBuilder = throw NotDefinedForRorProxy

  override def prepareGetFieldMappings(indices: String*): GetFieldMappingsRequestBuilder = throw NotDefinedForRorProxy

  override def getFieldMappings(request: GetFieldMappingsRequest, listener: ActionListener[GetFieldMappingsResponse]): Unit = {
    execute(GetFieldMappingsAction.INSTANCE.name(), request, listener) {
      esClient.getFieldMappings
    }
  }

  override def getFieldMappings(request: GetFieldMappingsRequest): ActionFuture[GetFieldMappingsResponse] = throw NotDefinedForRorProxy

  override def putMapping(request: PutMappingRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def putMapping(request: PutMappingRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(PutMappingAction.INSTANCE.name(), request, listener) {
      esClient.putMappings
    }
  }

  override def preparePutMapping(indices: String*): PutMappingRequestBuilder = throw NotDefinedForRorProxy

  override def aliases(request: IndicesAliasesRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def aliases(request: IndicesAliasesRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(IndicesAliasesAction.INSTANCE.name(), request, listener) {
      esClient.updateAliases
    }
  }

  override def prepareAliases(): IndicesAliasesRequestBuilder = throw NotDefinedForRorProxy

  override def getAliases(request: GetAliasesRequest): ActionFuture[GetAliasesResponse] = throw NotDefinedForRorProxy

  override def getAliases(request: GetAliasesRequest, listener: ActionListener[GetAliasesResponse]): Unit = {
    execute(GetAliasesAction.INSTANCE.name(), request, listener) { r =>
      esClient
        .getAlias(r)
        .map { resp =>
          Option(resp.getException) match {
            case Some(ex) => throw ex
            case None =>
              val aliases = ImmutableOpenMap
                .builder[String, java.util.List[AliasMetaData]]()
                .putAll(resp.getAliases.asScala.mapValues(_.asScala.toList.asJava).asJava)
                .build()
              new GetAliasesResponse(aliases)
          }
        }
    }
  }

  override def prepareGetAliases(aliases: String*): GetAliasesRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAliasesExist(aliases: String*): AliasesExistRequestBuilder = throw NotDefinedForRorProxy

  override def aliasesExist(request: GetAliasesRequest): ActionFuture[AliasesExistResponse] = throw NotDefinedForRorProxy

  override def aliasesExist(request: GetAliasesRequest, listener: ActionListener[AliasesExistResponse]): Unit = {
    execute(GetAliasesAction.INSTANCE.name(), request, listener) {
      esClient.aliasesExist
    }
  }

  override def getIndex(request: GetIndexRequest): ActionFuture[GetIndexResponse] = throw NotDefinedForRorProxy

  override def getIndex(request: GetIndexRequest, listener: ActionListener[GetIndexResponse]): Unit = {
    execute(GetIndexAction.INSTANCE.name(), request, listener) {
      esClient.getIndex
    }
  }

  override def prepareGetIndex(): GetIndexRequestBuilder = throw NotDefinedForRorProxy

  override def clearCache(request: ClearIndicesCacheRequest): ActionFuture[ClearIndicesCacheResponse] = throw NotDefinedForRorProxy

  override def clearCache(request: ClearIndicesCacheRequest, listener: ActionListener[ClearIndicesCacheResponse]): Unit = {
    execute(ClearIndicesCacheAction.INSTANCE.name(), request, listener) {
      esClient.clearCache
    }
  }

  override def prepareClearCache(indices: String*): ClearIndicesCacheRequestBuilder = throw NotDefinedForRorProxy

  override def updateSettings(request: UpdateSettingsRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def updateSettings(request: UpdateSettingsRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(UpdateSettingsAction.INSTANCE.name(), request, listener) {
      esClient.updateSettings
    }
  }

  override def prepareUpdateSettings(indices: String*): UpdateSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def analyze(request: AnalyzeAction.Request): ActionFuture[AnalyzeAction.Response] = throw NotDefinedForRorProxy

  override def analyze(request: AnalyzeAction.Request, listener: ActionListener[AnalyzeAction.Response]): Unit = {
    execute(AnalyzeAction.INSTANCE.name(), request, listener) {
      esClient.analyze
    }
  }

  override def prepareAnalyze(index: String, text: String): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAnalyze(text: String): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAnalyze(): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def putTemplate(request: PutIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def putTemplate(request: PutIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(PutIndexTemplateAction.INSTANCE.name(), request, listener) {
      esClient.putTemplate
    }
  }

  override def preparePutTemplate(name: String): PutIndexTemplateRequestBuilder = throw NotDefinedForRorProxy

  override def deleteTemplate(request: DeleteIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteTemplate(request: DeleteIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(DeleteIndexTemplateAction.INSTANCE.name(), request, listener) {
      esClient.deleteTemplate
    }
  }

  override def prepareDeleteTemplate(name: String): DeleteIndexTemplateRequestBuilder = throw NotDefinedForRorProxy

  override def getTemplates(request: GetIndexTemplatesRequest): ActionFuture[GetIndexTemplatesResponse] = throw NotDefinedForRorProxy

  override def getTemplates(request: GetIndexTemplatesRequest,
                            listener: ActionListener[GetIndexTemplatesResponse]): Unit = {
    execute(GetIndexTemplatesAction.INSTANCE.name(), request, listener) {
      esClient.getTemplate
    }
  }

  override def prepareGetTemplates(name: String*): GetIndexTemplatesRequestBuilder = throw NotDefinedForRorProxy

  override def validateQuery(request: ValidateQueryRequest): ActionFuture[ValidateQueryResponse] = throw NotDefinedForRorProxy

  override def validateQuery(request: ValidateQueryRequest, listener: ActionListener[ValidateQueryResponse]): Unit = {
    execute(ValidateQueryAction.INSTANCE.name(), request, listener) {
      esClient.validateQuery
    }
  }

  override def prepareValidateQuery(indices: String*): ValidateQueryRequestBuilder = throw NotDefinedForRorProxy

  override def getSettings(request: GetSettingsRequest, listener: ActionListener[GetSettingsResponse]): Unit = {
    execute(GetSettingsAction.INSTANCE.name(), request, listener) {
      esClient.getSettings
    }
  }

  override def getSettings(request: GetSettingsRequest): ActionFuture[GetSettingsResponse] = throw NotDefinedForRorProxy

  override def prepareGetSettings(indices: String*): GetSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def prepareResizeIndex(sourceIndex: String, targetIndex: String): ResizeRequestBuilder = throw NotDefinedForRorProxy

  override def resizeIndex(request: ResizeRequest): ActionFuture[ResizeResponse] = throw NotDefinedForRorProxy

  override def resizeIndex(request: ResizeRequest, listener: ActionListener[ResizeResponse]): Unit = {
    execute(ResizeAction.INSTANCE.name(), request, listener) {
      esClient.resizeIndex
    }
  }

  override def prepareRolloverIndex(sourceAlias: String): RolloverRequestBuilder = throw NotDefinedForRorProxy

  override def rolloversIndex(request: RolloverRequest): ActionFuture[RolloverResponse] = throw NotDefinedForRorProxy

  override def rolloverIndex(request: RolloverRequest, listener: ActionListener[RolloverResponse]): Unit = {
    execute(RolloverAction.INSTANCE.name(), request, listener) {
      esClient.rolloverIndex
    }
  }

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response],
                                                                             request: Request): ActionFuture[Response] =
    throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response],
                                                                             request: Request,
                                                                             listener: ActionListener[Response]): Unit =
    throw NotDefinedForRorProxy

  override def threadPool(): ThreadPool = throw NotDefinedForRorProxy

}
