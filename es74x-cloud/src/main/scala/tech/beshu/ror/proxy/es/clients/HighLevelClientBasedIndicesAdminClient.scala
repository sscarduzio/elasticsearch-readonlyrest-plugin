/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import monix.execution.Scheduler
import org.elasticsearch.action.admin.indices.alias.exists.{AliasesExistRequestBuilder, AliasesExistResponse}
import org.elasticsearch.action.admin.indices.alias.get.{GetAliasesRequest, GetAliasesRequestBuilder, GetAliasesResponse}
import org.elasticsearch.action.admin.indices.alias.{IndicesAliasesRequest, IndicesAliasesRequestBuilder}
import org.elasticsearch.action.admin.indices.analyze.{AnalyzeAction, AnalyzeRequestBuilder}
import org.elasticsearch.action.admin.indices.cache.clear.{ClearIndicesCacheRequest, ClearIndicesCacheRequestBuilder, ClearIndicesCacheResponse}
import org.elasticsearch.action.admin.indices.close.{CloseIndexRequest, CloseIndexRequestBuilder, CloseIndexResponse}
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexRequest, DeleteIndexRequestBuilder}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.admin.indices.exists.types.{TypesExistsRequest, TypesExistsRequestBuilder, TypesExistsResponse}
import org.elasticsearch.action.admin.indices.flush._
import org.elasticsearch.action.admin.indices.forcemerge.{ForceMergeRequest, ForceMergeRequestBuilder, ForceMergeResponse}
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexRequestBuilder, GetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get._
import org.elasticsearch.action.admin.indices.mapping.put.{PutMappingRequest, PutMappingRequestBuilder}
import org.elasticsearch.action.admin.indices.open.{OpenIndexRequest, OpenIndexRequestBuilder, OpenIndexResponse}
import org.elasticsearch.action.admin.indices.recovery.{RecoveryRequest, RecoveryRequestBuilder, RecoveryResponse}
import org.elasticsearch.action.admin.indices.refresh.{RefreshRequest, RefreshRequestBuilder, RefreshResponse}
import org.elasticsearch.action.admin.indices.rollover.{RolloverRequest, RolloverRequestBuilder, RolloverResponse}
import org.elasticsearch.action.admin.indices.segments.{IndicesSegmentResponse, IndicesSegmentsRequest, IndicesSegmentsRequestBuilder}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsRequestBuilder, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.settings.put.{UpdateSettingsRequest, UpdateSettingsRequestBuilder}
import org.elasticsearch.action.admin.indices.shards.{IndicesShardStoreRequestBuilder, IndicesShardStoresRequest, IndicesShardStoresResponse}
import org.elasticsearch.action.admin.indices.shrink.{ResizeRequest, ResizeRequestBuilder, ResizeResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsRequestBuilder, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.delete.{DeleteIndexTemplateRequest, DeleteIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest, GetIndexTemplatesRequestBuilder, GetIndexTemplatesResponse}
import org.elasticsearch.action.admin.indices.template.put.{PutIndexTemplateRequest, PutIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.upgrade.get.{UpgradeStatusRequest, UpgradeStatusRequestBuilder, UpgradeStatusResponse}
import org.elasticsearch.action.admin.indices.upgrade.post.{UpgradeRequest, UpgradeRequestBuilder, UpgradeResponse}
import org.elasticsearch.action.admin.indices.validate.query.{ValidateQueryRequest, ValidateQueryRequestBuilder, ValidateQueryResponse}
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.action._
import org.elasticsearch.client.IndicesAdminClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy

class HighLevelClientBasedIndicesAdminClient(esClient: RestHighLevelClientAdapter)
                                            (implicit scheduler: Scheduler)
  extends IndicesAdminClient  {

  override def exists(request: IndicesExistsRequest): ActionFuture[IndicesExistsResponse] = throw NotDefinedForRorProxy

  override def exists(request: IndicesExistsRequest, listener: ActionListener[IndicesExistsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareExists(indices: String*): IndicesExistsRequestBuilder = throw NotDefinedForRorProxy

  override def typesExists(request: TypesExistsRequest): ActionFuture[TypesExistsResponse] = throw NotDefinedForRorProxy

  override def typesExists(request: TypesExistsRequest, listener: ActionListener[TypesExistsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareTypesExists(index: String*): TypesExistsRequestBuilder = throw NotDefinedForRorProxy

  override def stats(request: IndicesStatsRequest): ActionFuture[IndicesStatsResponse] = throw NotDefinedForRorProxy

  override def stats(request: IndicesStatsRequest, listener: ActionListener[IndicesStatsResponse]): Unit = {
    esClient
      .stats(request)
      .runAsync(handleResultUsing(listener))
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

  override def delete(request: DeleteIndexRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareDelete(indices: String*): DeleteIndexRequestBuilder = throw NotDefinedForRorProxy

  override def close(request: CloseIndexRequest): ActionFuture[CloseIndexResponse] = throw NotDefinedForRorProxy

  override def close(request: CloseIndexRequest, listener: ActionListener[CloseIndexResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareClose(indices: String*): CloseIndexRequestBuilder = throw NotDefinedForRorProxy

  override def open(request: OpenIndexRequest): ActionFuture[OpenIndexResponse] = throw NotDefinedForRorProxy

  override def open(request: OpenIndexRequest, listener: ActionListener[OpenIndexResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareOpen(indices: String*): OpenIndexRequestBuilder = throw NotDefinedForRorProxy

  override def refresh(request: RefreshRequest): ActionFuture[RefreshResponse] = throw NotDefinedForRorProxy

  override def refresh(request: RefreshRequest, listener: ActionListener[RefreshResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareRefresh(indices: String*): RefreshRequestBuilder = throw NotDefinedForRorProxy

  override def flush(request: FlushRequest): ActionFuture[FlushResponse] = throw NotDefinedForRorProxy

  override def flush(request: FlushRequest, listener: ActionListener[FlushResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareFlush(indices: String*): FlushRequestBuilder = throw NotDefinedForRorProxy

  override def syncedFlush(request: SyncedFlushRequest): ActionFuture[SyncedFlushResponse] = throw NotDefinedForRorProxy

  override def syncedFlush(request: SyncedFlushRequest, listener: ActionListener[SyncedFlushResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareSyncedFlush(indices: String*): SyncedFlushRequestBuilder = throw NotDefinedForRorProxy

  override def forceMerge(request: ForceMergeRequest): ActionFuture[ForceMergeResponse] = throw NotDefinedForRorProxy

  override def forceMerge(request: ForceMergeRequest, listener: ActionListener[ForceMergeResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareForceMerge(indices: String*): ForceMergeRequestBuilder = throw NotDefinedForRorProxy

  override def upgrade(request: UpgradeRequest): ActionFuture[UpgradeResponse] = throw NotDefinedForRorProxy

  override def upgrade(request: UpgradeRequest, listener: ActionListener[UpgradeResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpgradeStatus(indices: String*): UpgradeStatusRequestBuilder = throw NotDefinedForRorProxy

  override def upgradeStatus(request: UpgradeStatusRequest): ActionFuture[UpgradeStatusResponse] = throw NotDefinedForRorProxy

  override def upgradeStatus(request: UpgradeStatusRequest, listener: ActionListener[UpgradeStatusResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpgrade(indices: String*): UpgradeRequestBuilder = throw NotDefinedForRorProxy

  override def getMappings(request: GetMappingsRequest, listener: ActionListener[GetMappingsResponse]): Unit = throw NotDefinedForRorProxy

  override def getMappings(request: GetMappingsRequest): ActionFuture[GetMappingsResponse] = throw NotDefinedForRorProxy

  override def prepareGetMappings(indices: String*): GetMappingsRequestBuilder = throw NotDefinedForRorProxy

  override def getFieldMappings(request: GetFieldMappingsRequest, listener: ActionListener[GetFieldMappingsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetFieldMappings(indices: String*): GetFieldMappingsRequestBuilder = throw NotDefinedForRorProxy

  override def getFieldMappings(request: GetFieldMappingsRequest): ActionFuture[GetFieldMappingsResponse] = throw NotDefinedForRorProxy

  override def putMapping(request: PutMappingRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def putMapping(request: PutMappingRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def preparePutMapping(indices: String*): PutMappingRequestBuilder = throw NotDefinedForRorProxy

  override def aliases(request: IndicesAliasesRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def aliases(request: IndicesAliasesRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareAliases(): IndicesAliasesRequestBuilder = throw NotDefinedForRorProxy

  override def getAliases(request: GetAliasesRequest): ActionFuture[GetAliasesResponse] = throw NotDefinedForRorProxy

  override def getAliases(request: GetAliasesRequest, listener: ActionListener[GetAliasesResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetAliases(aliases: String*): GetAliasesRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAliasesExist(aliases: String*): AliasesExistRequestBuilder = throw NotDefinedForRorProxy

  override def aliasesExist(request: GetAliasesRequest): ActionFuture[AliasesExistResponse] = throw NotDefinedForRorProxy

  override def aliasesExist(request: GetAliasesRequest, listener: ActionListener[AliasesExistResponse]): Unit = throw NotDefinedForRorProxy

  override def getIndex(request: GetIndexRequest): ActionFuture[GetIndexResponse] = throw NotDefinedForRorProxy

  override def getIndex(request: GetIndexRequest, listener: ActionListener[GetIndexResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetIndex(): GetIndexRequestBuilder = throw NotDefinedForRorProxy

  override def clearCache(request: ClearIndicesCacheRequest): ActionFuture[ClearIndicesCacheResponse] = throw NotDefinedForRorProxy

  override def clearCache(request: ClearIndicesCacheRequest, listener: ActionListener[ClearIndicesCacheResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareClearCache(indices: String*): ClearIndicesCacheRequestBuilder = throw NotDefinedForRorProxy

  override def updateSettings(request: UpdateSettingsRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def updateSettings(request: UpdateSettingsRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpdateSettings(indices: String*): UpdateSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def analyze(request: AnalyzeAction.Request): ActionFuture[AnalyzeAction.Response] = throw NotDefinedForRorProxy

  override def analyze(request: AnalyzeAction.Request, listener: ActionListener[AnalyzeAction.Response]): Unit = throw NotDefinedForRorProxy

  override def prepareAnalyze(index: String, text: String): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAnalyze(text: String): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def prepareAnalyze(): AnalyzeRequestBuilder = throw NotDefinedForRorProxy

  override def putTemplate(request: PutIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def putTemplate(request: PutIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def preparePutTemplate(name: String): PutIndexTemplateRequestBuilder = throw NotDefinedForRorProxy

  override def deleteTemplate(request: DeleteIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteTemplate(request: DeleteIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareDeleteTemplate(name: String): DeleteIndexTemplateRequestBuilder = throw NotDefinedForRorProxy

  override def getTemplates(request: GetIndexTemplatesRequest): ActionFuture[GetIndexTemplatesResponse] = throw NotDefinedForRorProxy

  override def getTemplates(request: GetIndexTemplatesRequest, listener: ActionListener[GetIndexTemplatesResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetTemplates(name: String*): GetIndexTemplatesRequestBuilder = throw NotDefinedForRorProxy

  override def validateQuery(request: ValidateQueryRequest): ActionFuture[ValidateQueryResponse] = throw NotDefinedForRorProxy

  override def validateQuery(request: ValidateQueryRequest, listener: ActionListener[ValidateQueryResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareValidateQuery(indices: String*): ValidateQueryRequestBuilder = throw NotDefinedForRorProxy

  override def getSettings(request: GetSettingsRequest, listener: ActionListener[GetSettingsResponse]): Unit = {
    esClient
      .getSettings(request)
      .runAsync(handleResultUsing(listener))
  }

  override def getSettings(request: GetSettingsRequest): ActionFuture[GetSettingsResponse] = throw NotDefinedForRorProxy

  override def prepareGetSettings(indices: String*): GetSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def prepareResizeIndex(sourceIndex: String, targetIndex: String): ResizeRequestBuilder = throw NotDefinedForRorProxy

  override def resizeIndex(request: ResizeRequest): ActionFuture[ResizeResponse] = throw NotDefinedForRorProxy

  override def resizeIndex(request: ResizeRequest, listener: ActionListener[ResizeResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareRolloverIndex(sourceAlias: String): RolloverRequestBuilder = throw NotDefinedForRorProxy

  override def rolloversIndex(request: RolloverRequest): ActionFuture[RolloverResponse] = throw NotDefinedForRorProxy

  override def rolloverIndex(request: RolloverRequest, listener: ActionListener[RolloverResponse]): Unit = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = throw NotDefinedForRorProxy

  override def threadPool(): ThreadPool = throw NotDefinedForRorProxy


  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }
}
