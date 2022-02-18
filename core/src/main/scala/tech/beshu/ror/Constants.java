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

package tech.beshu.ror;

import com.google.common.collect.Sets;

import java.util.Set;

public class Constants {
  public static final Integer CACHE_WATERMARK = 1024;
  public static final String ANSI_RESET = "\u001B[0m";
  public static final String ANSI_YELLOW = "\u001B[33m";
  public static final String ANSI_PURPLE = "\u001B[35m";
  public static final String ANSI_CYAN = "\u001B[36m";
  public static final Integer AUDIT_SINK_MAX_ITEMS = 100;
  public static final Integer AUDIT_SINK_MAX_KB = 100;
  public static final Integer AUDIT_SINK_MAX_SECONDS = 2;
  public static final Integer AUDIT_SINK_MAX_RETRIES = 3;
  public static final Integer MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES = 5 * 1000;
  public final static String CURRENT_USER_METADATA_PATH = "/_readonlyrest/metadata/current_user/";
  public final static String CONFIGURE_AUTH_MOCK_PATH = "/_readonlyrest/admin/authmock/";
  public final static String AUDIT_EVENT_COLLECTOR_PATH = "/_readonlyrest/admin/audit/event/";
  public final static String FORCE_RELOAD_CONFIG_PATH = "/_readonlyrest/admin/refreshconfig/";
  public final static String UPDATE_INDEX_CONFIG_PATH = "/_readonlyrest/admin/config/";
  public final static String PROVIDE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/";
  public final static String UPDATE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/";
  public final static String DELETE_TEST_CONFIG_PATH = "/_readonlyrest/admin/config/test/";
  public final static String PROVIDE_LOCAL_USERS_PATH = "/_readonlyrest/admin/config/test/localusers/";
  public final static String PROVIDE_INDEX_CONFIG_PATH = "/_readonlyrest/admin/config/";
  public final static String PROVIDE_FILE_CONFIG_PATH = "/_readonlyrest/admin/config/file/";
  public final static String MANAGE_ROR_CONFIG_PATH = "/_readonlyrest/admin/config/load";

  public final static String FIELDS_TRANSIENT = "_fields";

  public final static Set<String> FIELDS_ALWAYS_ALLOW = Sets.newHashSet("_id", "_uid", "_type", "_version", "_seq_no", "_primary_term", "_parent", "_routing", "_timestamp", "_ttl", "_size", "_index");

  public static final String AUDIT_LOG_DEFAULT_INDEX_TEMPLATE = "'readonlyrest_audit-'yyyy-MM-dd";

  public static final String HEADER_LOGGING_ID            = "x-ror-logging-id";
  public static final String HEADER_GROUPS_AVAILABLE      = "x-ror-available-groups";
  public static final String HEADER_GROUP_CURRENT         = "x-ror-current-group";
  public static final String HEADER_USER_ROR              = "x-ror-username";
  public static final String HEADER_KIBANA_HIDDEN_APPS    = "x-ror-kibana-hidden-apps";
  public static final String HEADER_KIBANA_ACCESS         = "x-ror-kibana_access";
  public static final String HEADER_KIBANA_INDEX          = "x-ror-kibana_index";
  public static final String HEADER_KIBANA_TEMPLATE_INDEX = "x-ror-kibana_template_index";
  public static final String HEADER_USER_ORIGIN           = "x-ror-origin";
  public static final String HEADER_CORRELATION_ID        = "x-ror-correlation-id";
  public static final String HEADER_IMPERSONATING         = "x-ror-impersonating";
  public static final String HEADER_AUTH_MOCK_TTL         = "x-ror-auth-mock-ttl";

  public static final Set<String> RO_ACTIONS = Sets.newHashSet(
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
  );

  public static final Set<String> CLUSTER_ACTIONS = Sets.newHashSet(
      "cluster:monitor/nodes/info",
      "cluster:monitor/main",
      "cluster:monitor/health",
      "cluster:monitor/state",
      "cluster:monitor/ccr/follow_info",
      "cluster:*/xpack/*",
      "indices:admin/template/get*",
      "cluster:*/info",
      "cluster:*/get"
  );

  public static final Set<String> RW_ACTIONS = Sets.newHashSet(
      "indices:admin/create",
      "indices:admin/create_index",
      "indices:admin/mapping/put",
      "indices:data/write/delete*",
      "indices:data/write/index",
      "indices:data/write/update*",
      "indices:data/write/bulk*",
      "indices:admin/template/*",
      "cluster:admin/settings/*",
      "indices:admin/aliases/*"
  );

  public static final Set<String> ADMIN_ACTIONS = Sets.newHashSet(
      "cluster:ror/*",
      "indices:data/write/*", // <-- DEPRECATED!
      "indices:admin/create",
      "indices:admin/create_index",
      "indices:admin/ilm/*",
      "indices:monitor/*"
  );
}
