package tech.beshu.ror.utils.elasticsearch

import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}

class ClusterManager(client: RestClient,
                     esVersion: String)
  extends BaseManager(client)  {

  def allocationExplain(index: String): JsonResponse = {
    call(createAllocationExplainRequest(index), new JsonResponse(_))
  }

  private def createAllocationExplainRequest(index: String) = {
    val request = new HttpGetWithEntity(client.from("_cluster/allocation/explain"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{
         |"index":"$index",
         |"shard": 0,
         |"primary": true
         |}""".stripMargin
    ))
    request
  }
}
