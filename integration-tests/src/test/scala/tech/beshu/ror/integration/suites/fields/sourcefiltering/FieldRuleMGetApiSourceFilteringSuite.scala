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
package tech.beshu.ror.integration.suites.fields.sourcefiltering

import tech.beshu.ror.integration.suites.fields.sourcefiltering.FieldRuleSourceFilteringSuite.ClientSourceOptions.{DoNotFetchSource, Exclude, Include}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.elasticsearch.DocumentManager.MGetResult
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldRuleMGetApiSourceFilteringSuite
  extends FieldRuleSourceFilteringSuite {
  this: EsClusterProvider with ESVersionSupport =>

  override protected type CALL_RESULT = MGetResult

  override protected def fetchDocument(client: RestClient,
                                       index: String,
                                       clientSourceParams: Option[FieldRuleSourceFilteringSuite.ClientSourceOptions]): MGetResult = {
    val documentManager = new DocumentManager(client, esVersionUsed)

    val queryParams = clientSourceParams match {
      case Some(DoNotFetchSource) => Map("_source" -> "false")
      case Some(Include(field)) => Map("_source_includes" -> field)
      case Some(Exclude(field)) => Map("_source_excludes" -> field)
      case None => Map.empty[String, String]
    }

    documentManager.mGet(
      ujson.read(
        s"""{
           |  "docs":[
           |    {
           |      "_index":"$index",
           |      "_id":1
           |    }
           |  ]
           |}""".stripMargin
      ),
      queryParams
    )
  }

  override protected def sourceOfFirstDoc(result: MGetResult): Option[JSON] = {
    result.docs.head.obj.get("_source")
  }
}