package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient

class IndexManager(client: RestClient)
  extends BaseManager(client) {

  def createAliasOf(index: String, alias: String): JsonResponse = {
    call(createAliasRequest(index, alias), new JsonResponse(_))
  }

  def createAliasAndAssert(index: String, alias: String): Unit = {
    val result = createAliasOf(index, alias)
    if(!result.isSuccess) {
      throw new IllegalStateException(s"Cannot create alias '$alias'; returned: ${result.body}")
    }
  }

  private def createAliasRequest(index: String, alias: String) = {
    val request = new HttpPost(client.from("_aliases"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(
      s"""{"actions":[{"add":{"index":"$index","alias":"$alias"}}]}""".stripMargin))
    request
  }
}
