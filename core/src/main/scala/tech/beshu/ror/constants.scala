package tech.beshu.ror

import tech.beshu.ror.accesscontrol.domain.Action
import scala.collection.mutable.{Set => MutableSet}

object constants {

  val CACHE_WATERMARK = 1024
  val ANSI_RESET = "\u001B[0m"
  val ANSI_YELLOW = "\u001B[33m"
  val ANSI_PURPLE = "\u001B[35m"
  val ANSI_CYAN = "\u001B[36m"
  val AUDIT_SINK_MAX_ITEMS = 100
  val AUDIT_SINK_MAX_KB = 100
  val AUDIT_SINK_MAX_SECONDS = 2
  val AUDIT_SINK_MAX_RETRIES = 3
  val MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES: Integer = 5 * 1000
  val CURRENT_USER_METADATA_PATH = "/_readonlyrest/metadata/current_user/"
  val AUDIT_EVENT_COLLECTOR_PATH = "/_readonlyrest/admin/audit/event/"
  val FORCE_RELOAD_CONFIG_PATH = "/_readonlyrest/admin/refreshconfig/"
  val UPDATE_INDEX_CONFIG_PATH = "/_readonlyrest/admin/config/"
  val PROVIDE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/"
  val UPDATE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/"
  val DELETE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/"
  val PROVIDE_LOCAL_USERS_PATH = "/_readonlyrest/admin/config/test/localusers/"
  val CONFIGURE_AUTH_MOCK_PATH = "/_readonlyrest/admin/config/test/authmock/"
  val PROVIDE_AUTH_MOCK_PATH = "/_readonlyrest/admin/config/test/authmock/"
  val PROVIDE_INDEX_CONFIG_PATH = "/_readonlyrest/admin/config/"
  val PROVIDE_FILE_CONFIG_PATH = "/_readonlyrest/admin/config/file/"
  val MANAGE_ROR_CONFIG_PATH = "/_readonlyrest/admin/config/load"

  val FIELDS_TRANSIENT = "_fields"

  val FIELDS_ALWAYS_ALLOW: MutableSet[String] =
    MutableSet("_id", "_uid", "_type", "_version", "_seq_no", "_primary_term", "_parent", "_routing", "_timestamp", "_ttl", "_size", "_index")

  val AUDIT_LOG_DEFAULT_INDEX_TEMPLATE = "'readonlyrest_audit-'yyyy-MM-dd"
  val roActionPatterns: Set[Action] = Set(
    "indices:admin/exists",
    "indices:admin/mappings/fields/get*",
    "indices:admin/mappings/get*",
    "indices:admin/validate/query",
    "indices:admin/get",
    "indices:admin/refresh*",
    "indices:data/read/*",
    "indices:admin/resolve/*",
    "indices:admin/aliases/get",
    "indices:admin/*/explain",
    "indices:monitor/settings/get",
    "indices:data/read/xpack/rollup/get/*",
    "indices:monitor/stats"
  ).map(Action.apply)

  val clusterActionPatterns: Set[Action] = Set(
    "cluster:monitor/nodes/info",
    "cluster:monitor/main",
    "cluster:monitor/health",
    "cluster:monitor/state",
    "cluster:monitor/ccr/follow_info",
    "cluster:*/xpack/*",
    "indices:admin/template/get*",
    "cluster:*/info",
    "cluster:*/get"
  ).map(Action.apply)

  val rwActionPatterns: Set[Action] = Set(
    "indices:admin/create",
    "indices:admin/create_index",
    "indices:admin/mapping/put",
    "indices:data/write/delete*",
    "indices:data/write/index",
    "indices:data/write/update*",
    "indices:data/write/bulk*",
    "indices:admin/template/*",
    "cluster:admin/component_template/*",
    "cluster:admin/settings/*",
    "indices:admin/aliases/*",
    "cluster:monitor/health_api",
    "cluster:admin/migration/get_system_feature",
    "cluster:monitor/settings"
  ).map(Action.apply)

  val adminActionPatterns: Set[Action] = Set(
    "cluster:internal_ror/*",
    "indices:data/write/*", // <-- DEPRECATED!
    "indices:admin/create",
    "indices:admin/create_index",
    "indices:admin/ilm/*",
    "indices:monitor/*",
    "cluster:admin/ingest/pipeline/put",
    "cluster:admin/ingest/pipeline/delete",
    "indices:admin/data_stream/get",
    "indices:admin/settings/update",
    "cluster:admin/ilm/put",
    "cluster:admin/ilm/delete",
    "cluster:admin/repository/put",
    "cluster:admin/repository/delete",
    "indices:data/write/update/byquery",
    "indices:admin/data_stream/delete",
    "cluster:admin/component_template/get",
    "cluster:admin/component_template/delete",
    "cluster:admin/component_template/put",
    "cluster:monitor/ccr/follow_info",
    "indices:admin/delete",
    "indices:admin/index_template/get",
    "indices:admin/index_template/delete",
    "indices:admin/index_template/put",
    "indices:admin/index_template/simulate",
    "cluster:admin/slm/put",
    "cluster:admin/slm/execute",
    "cluster:admin/slm/delete"
  ).map(Action.apply)
}
