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

class HighLevelClientBasedIndicesAdminClient(esClient: RestHighLevelClientAdapter)
                                            (implicit scheduler: Scheduler)
  extends IndicesAdminClient  {

  override def exists(request: IndicesExistsRequest): ActionFuture[IndicesExistsResponse] = ???

  override def exists(request: IndicesExistsRequest, listener: ActionListener[IndicesExistsResponse]): Unit = ???

  override def prepareExists(indices: String*): IndicesExistsRequestBuilder = ???

  override def typesExists(request: TypesExistsRequest): ActionFuture[TypesExistsResponse] = ???

  override def typesExists(request: TypesExistsRequest, listener: ActionListener[TypesExistsResponse]): Unit = ???

  override def prepareTypesExists(index: String*): TypesExistsRequestBuilder = ???

  override def stats(request: IndicesStatsRequest): ActionFuture[IndicesStatsResponse] = ???

  override def stats(request: IndicesStatsRequest, listener: ActionListener[IndicesStatsResponse]): Unit = {
    esClient
      .stats(request)
      .runAsync(handleResultUsing(listener))
  }

  override def prepareStats(indices: String*): IndicesStatsRequestBuilder = ???

  override def recoveries(request: RecoveryRequest): ActionFuture[RecoveryResponse] = ???

  override def recoveries(request: RecoveryRequest, listener: ActionListener[RecoveryResponse]): Unit = ???

  override def prepareRecoveries(indices: String*): RecoveryRequestBuilder = ???

  override def segments(request: IndicesSegmentsRequest): ActionFuture[IndicesSegmentResponse] = ???

  override def segments(request: IndicesSegmentsRequest, listener: ActionListener[IndicesSegmentResponse]): Unit = ???

  override def prepareSegments(indices: String*): IndicesSegmentsRequestBuilder = ???

  override def shardStores(request: IndicesShardStoresRequest): ActionFuture[IndicesShardStoresResponse] = ???

  override def shardStores(request: IndicesShardStoresRequest, listener: ActionListener[IndicesShardStoresResponse]): Unit = ???

  override def prepareShardStores(indices: String*): IndicesShardStoreRequestBuilder = ???

  override def create(request: CreateIndexRequest): ActionFuture[CreateIndexResponse] = ???

  override def create(request: CreateIndexRequest, listener: ActionListener[CreateIndexResponse]): Unit = ???

  override def prepareCreate(index: String): CreateIndexRequestBuilder = ???

  override def delete(request: DeleteIndexRequest): ActionFuture[AcknowledgedResponse] = ???

  override def delete(request: DeleteIndexRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def prepareDelete(indices: String*): DeleteIndexRequestBuilder = ???

  override def close(request: CloseIndexRequest): ActionFuture[CloseIndexResponse] = ???

  override def close(request: CloseIndexRequest, listener: ActionListener[CloseIndexResponse]): Unit = ???

  override def prepareClose(indices: String*): CloseIndexRequestBuilder = ???

  override def open(request: OpenIndexRequest): ActionFuture[OpenIndexResponse] = ???

  override def open(request: OpenIndexRequest, listener: ActionListener[OpenIndexResponse]): Unit = ???

  override def prepareOpen(indices: String*): OpenIndexRequestBuilder = ???

  override def refresh(request: RefreshRequest): ActionFuture[RefreshResponse] = ???

  override def refresh(request: RefreshRequest, listener: ActionListener[RefreshResponse]): Unit = ???

  override def prepareRefresh(indices: String*): RefreshRequestBuilder = ???

  override def flush(request: FlushRequest): ActionFuture[FlushResponse] = ???

  override def flush(request: FlushRequest, listener: ActionListener[FlushResponse]): Unit = ???

  override def prepareFlush(indices: String*): FlushRequestBuilder = ???

  override def syncedFlush(request: SyncedFlushRequest): ActionFuture[SyncedFlushResponse] = ???

  override def syncedFlush(request: SyncedFlushRequest, listener: ActionListener[SyncedFlushResponse]): Unit = ???

  override def prepareSyncedFlush(indices: String*): SyncedFlushRequestBuilder = ???

  override def forceMerge(request: ForceMergeRequest): ActionFuture[ForceMergeResponse] = ???

  override def forceMerge(request: ForceMergeRequest, listener: ActionListener[ForceMergeResponse]): Unit = ???

  override def prepareForceMerge(indices: String*): ForceMergeRequestBuilder = ???

  override def upgrade(request: UpgradeRequest): ActionFuture[UpgradeResponse] = ???

  override def upgrade(request: UpgradeRequest, listener: ActionListener[UpgradeResponse]): Unit = ???

  override def prepareUpgradeStatus(indices: String*): UpgradeStatusRequestBuilder = ???

  override def upgradeStatus(request: UpgradeStatusRequest): ActionFuture[UpgradeStatusResponse] = ???

  override def upgradeStatus(request: UpgradeStatusRequest, listener: ActionListener[UpgradeStatusResponse]): Unit = ???

  override def prepareUpgrade(indices: String*): UpgradeRequestBuilder = ???

  override def getMappings(request: GetMappingsRequest, listener: ActionListener[GetMappingsResponse]): Unit = ???

  override def getMappings(request: GetMappingsRequest): ActionFuture[GetMappingsResponse] = ???

  override def prepareGetMappings(indices: String*): GetMappingsRequestBuilder = ???

  override def getFieldMappings(request: GetFieldMappingsRequest, listener: ActionListener[GetFieldMappingsResponse]): Unit = ???

  override def prepareGetFieldMappings(indices: String*): GetFieldMappingsRequestBuilder = ???

  override def getFieldMappings(request: GetFieldMappingsRequest): ActionFuture[GetFieldMappingsResponse] = ???

  override def putMapping(request: PutMappingRequest): ActionFuture[AcknowledgedResponse] = ???

  override def putMapping(request: PutMappingRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def preparePutMapping(indices: String*): PutMappingRequestBuilder = ???

  override def aliases(request: IndicesAliasesRequest): ActionFuture[AcknowledgedResponse] = ???

  override def aliases(request: IndicesAliasesRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def prepareAliases(): IndicesAliasesRequestBuilder = ???

  override def getAliases(request: GetAliasesRequest): ActionFuture[GetAliasesResponse] = ???

  override def getAliases(request: GetAliasesRequest, listener: ActionListener[GetAliasesResponse]): Unit = ???

  override def prepareGetAliases(aliases: String*): GetAliasesRequestBuilder = ???

  override def prepareAliasesExist(aliases: String*): AliasesExistRequestBuilder = ???

  override def aliasesExist(request: GetAliasesRequest): ActionFuture[AliasesExistResponse] = ???

  override def aliasesExist(request: GetAliasesRequest, listener: ActionListener[AliasesExistResponse]): Unit = ???

  override def getIndex(request: GetIndexRequest): ActionFuture[GetIndexResponse] = ???

  override def getIndex(request: GetIndexRequest, listener: ActionListener[GetIndexResponse]): Unit = ???

  override def prepareGetIndex(): GetIndexRequestBuilder = ???

  override def clearCache(request: ClearIndicesCacheRequest): ActionFuture[ClearIndicesCacheResponse] = ???

  override def clearCache(request: ClearIndicesCacheRequest, listener: ActionListener[ClearIndicesCacheResponse]): Unit = ???

  override def prepareClearCache(indices: String*): ClearIndicesCacheRequestBuilder = ???

  override def updateSettings(request: UpdateSettingsRequest): ActionFuture[AcknowledgedResponse] = ???

  override def updateSettings(request: UpdateSettingsRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def prepareUpdateSettings(indices: String*): UpdateSettingsRequestBuilder = ???

  override def analyze(request: AnalyzeAction.Request): ActionFuture[AnalyzeAction.Response] = ???

  override def analyze(request: AnalyzeAction.Request, listener: ActionListener[AnalyzeAction.Response]): Unit = ???

  override def prepareAnalyze(index: String, text: String): AnalyzeRequestBuilder = ???

  override def prepareAnalyze(text: String): AnalyzeRequestBuilder = ???

  override def prepareAnalyze(): AnalyzeRequestBuilder = ???

  override def putTemplate(request: PutIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = ???

  override def putTemplate(request: PutIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def preparePutTemplate(name: String): PutIndexTemplateRequestBuilder = ???

  override def deleteTemplate(request: DeleteIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = ???

  override def deleteTemplate(request: DeleteIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

  override def prepareDeleteTemplate(name: String): DeleteIndexTemplateRequestBuilder = ???

  override def getTemplates(request: GetIndexTemplatesRequest): ActionFuture[GetIndexTemplatesResponse] = ???

  override def getTemplates(request: GetIndexTemplatesRequest, listener: ActionListener[GetIndexTemplatesResponse]): Unit = ???

  override def prepareGetTemplates(name: String*): GetIndexTemplatesRequestBuilder = ???

  override def validateQuery(request: ValidateQueryRequest): ActionFuture[ValidateQueryResponse] = ???

  override def validateQuery(request: ValidateQueryRequest, listener: ActionListener[ValidateQueryResponse]): Unit = ???

  override def prepareValidateQuery(indices: String*): ValidateQueryRequestBuilder = ???

  override def getSettings(request: GetSettingsRequest, listener: ActionListener[GetSettingsResponse]): Unit = {
    esClient
      .getSettings(request)
      .runAsync(handleResultUsing(listener))
  }

  override def getSettings(request: GetSettingsRequest): ActionFuture[GetSettingsResponse] = ???

  override def prepareGetSettings(indices: String*): GetSettingsRequestBuilder = ???

  override def prepareResizeIndex(sourceIndex: String, targetIndex: String): ResizeRequestBuilder = ???

  override def resizeIndex(request: ResizeRequest): ActionFuture[ResizeResponse] = ???

  override def resizeIndex(request: ResizeRequest, listener: ActionListener[ResizeResponse]): Unit = ???

  override def prepareRolloverIndex(sourceAlias: String): RolloverRequestBuilder = ???

  override def rolloversIndex(request: RolloverRequest): ActionFuture[RolloverResponse] = ???

  override def rolloverIndex(request: RolloverRequest, listener: ActionListener[RolloverResponse]): Unit = ???

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = ???

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = ???

  override def threadPool(): ThreadPool = ???


  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }
}
