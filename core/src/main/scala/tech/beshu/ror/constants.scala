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
package tech.beshu.ror

import scala.collection.mutable.Set as MutableSet

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

  val FIELDS_TRANSIENT = "_fields"

  val FIELDS_ALWAYS_ALLOW: MutableSet[String] =
    MutableSet("_id", "_uid", "_type", "_version", "_seq_no", "_primary_term", "_parent", "_routing", "_timestamp", "_ttl", "_size", "_index")

  val AUDIT_LOG_DEFAULT_INDEX_TEMPLATE = "'readonlyrest_audit-'yyyy-MM-dd"

}
