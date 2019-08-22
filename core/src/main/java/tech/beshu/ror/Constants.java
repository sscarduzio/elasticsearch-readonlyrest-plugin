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
  public final static String CURRENT_USER_METADATA_PATH = "/_readonlyrest/metadata/current_user";
  public final static String FILTER_TRANSIENT = "_filter";
  public final static String FIELDS_TRANSIENT = "_fields";
  public final static Set<String> FIELDS_ALWAYS_ALLOW = Sets.newHashSet("_id", "_uid", "_type", "_parent", "_routing", "_timestamp", "_ttl", "_size", "_index");

  public static final String AUDIT_LOG_DEFAULT_INDEX_TEMPLATE = "'readonlyrest_audit-'yyyy-MM-dd";

  public static final String HEADER_GROUPS_AVAILABLE = "x-ror-available-groups";
  public static final String HEADER_GROUP_CURRENT = "x-ror-current-group";
  public static final String HEADER_USER_ROR = "x-ror-username";
  public static final String HEADER_KIBANA_HIDDEN_APPS = "x-ror-kibana-hidden-apps";
  public static final String HEADER_KIBANA_ACCESS = "x-ror-kibana_access";
  public static final String HEADER_KIBANA_INDEX = "x-ror-kibana_index";
  public static final String HEADER_USER_ORIGIN = "x-ror-origin";

  public static final Set<String> RO_ACTIONS = Sets.newHashSet(
      "indices:admin/exists",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/validate/query",
      "indices:admin/get",
      "indices:admin/refresh*",
      "indices:data/read/*",
      "indices:admin/aliases/get",
      "indices:admin/ilm/explain",
      "indices:monitor/settings/get",
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
      "cluster:admin/rradmin/*",
      "indices:data/write/*", // <-- DEPRECATED!
      "indices:admin/create",
      "indices:admin/ilm/*",
      "indices:monitor/*"
  );
}
