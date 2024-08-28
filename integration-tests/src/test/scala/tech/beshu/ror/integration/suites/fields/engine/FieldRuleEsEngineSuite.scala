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
package tech.beshu.ror.integration.suites.fields.engine

import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.elasticsearch.SearchManager

class FieldRuleEsEngineSuite
  extends FieldRuleEngineSuite
    with SingletonPluginTestSupport {

  override implicit val rorConfigFileName: String = "/field_level_security_engine/readonlyrest_fls_engine_es.yml"

  override protected def unmodifiableQueryAssertion(result: SearchManager#SearchResult): Unit = {
    result should have statusCode 403
  }

  override protected def scrollSearchShouldProperlyHandleAllowedFields(result: SearchManager#SearchResult): Unit = {
    result should have statusCode 403
  }

  override protected def scrollSearchShouldProperlyHandleForbiddenFields(result: SearchManager#SearchResult): Unit = {
    result should have statusCode 403
  }
}