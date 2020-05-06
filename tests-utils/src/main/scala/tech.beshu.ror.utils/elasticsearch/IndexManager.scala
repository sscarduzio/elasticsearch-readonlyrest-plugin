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
package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.httpclient.RestClient

class IndexManager(client: RestClient,
                   override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  def getIndex(indices: String*): JsonResponse = {
    call(getIndexRequest(indices.toSet), new JsonResponse(_))
  }

  def getAliases: JsonResponse = {
    call(getAliasRequest(), new JsonResponse(_))
  }

  def getAlias(indices: String*): JsonResponse = {
    val indexOpt = indices.mkString(",") match {
      case "" => None
      case str => Some(str)
    }
    call(getAliasRequest(indexOpt), new JsonResponse(_))
  }

  def getAliasByName(index: String, alias: String): JsonResponse = {
    call(getAliasRequest(Some(index), Some(alias)), new JsonResponse(_))
  }

  def createAliasOf(index: String, alias: String): JsonResponse = {
    call(createAliasRequest(index, alias), new JsonResponse(_))
  }

  def createAliasAndAssert(index: String, alias: String): Unit = {
    val result = createAliasOf(index, alias)
    if(!result.isSuccess) {
      throw new IllegalStateException(s"Cannot create alias '$alias'; returned: ${result.body}")
    }
  }

  def getSettings(index: String*): JsonResponse = {
    call(createGetSettingsRequest(index.toSet), new JsonResponse(_))
  }

  def getAllSettings: JsonResponse = {
    call(createGetSettingsRequest(Set("_all")), new JsonResponse(_))
  }

  def removeAll: SimpleResponse =
    call(createDeleteIndicesRequest, new SimpleResponse(_))

  private def getAliasRequest(indexOpt: Option[String] = None,
                              aliasOpt: Option[String] = None) = {
    val path = indexOpt match {
      case Some(index) =>
        aliasOpt match {
          case Some(alias) => s"$index/_alias/$alias"
          case None => s"$index/_alias"
        }
      case None =>
        aliasOpt match {
          case Some(alias) => s"_alias/$alias"
          case None => "_alias"
        }
    }
    new HttpGet(client.from(path))
  }

  private def getIndexRequest(indices: Set[String]) = {
    new HttpGet(client.from(indices.mkString(",")))
  }

  private def createAliasRequest(index: String, alias: String) = {
    val request = new HttpPost(client.from("_aliases"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{"actions":[{"add":{"index":"$index","alias":"$alias"}}]}""".stripMargin))
    request
  }

  private def createGetSettingsRequest(indices: Set[String]) = {
    new HttpGet(client.from(s"${indices.mkString(",")}/_settings"))
  }

  private def createDeleteIndicesRequest = {
    new HttpDelete(client.from("/_all"))
  }

}
