package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.HttpGet
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}

import scala.collection.JavaConverters._

class ClusterManager(client: RestClient,
                     esVersion: String)
  extends BaseManager(client)  {

  def allocationExplain(): JsonResponse = allocationExplain(None)

  def allocationExplain(index: String): JsonResponse = allocationExplain(Some(index))

  def health(): JsonResponse = health(None)

  def health(index: String): JsonResponse = health(Some(index))

  def state(indices: String*): JsonResponse = {
    call(createStateRequest(indices), new JsonResponse(_))
  }

  private def allocationExplain(index: Option[String]): JsonResponse = {
    call(createAllocationExplainRequest(index), new JsonResponse(_))
  }

  private def health(index: Option[String]): JsonResponse = {
    call(createHealthRequest(index), new JsonResponse(_))
  }

  private def createAllocationExplainRequest(index: Option[String]) = {
    val request = new HttpGetWithEntity(client.from("_cluster/allocation/explain"))
    index match {
      case Some(indexName) =>
        request.addHeader("Content-Type", "application/json")
        request.setEntity(new StringEntity(
          s"""{
             |"index":"$indexName",
             |"shard": 0,
             |"primary": true
             |}""".stripMargin
        ))
      case None =>
    }
    request
  }

  private def createHealthRequest(index: Option[String]) = {
    new HttpGet(client.from(
      index match {
        case Some(value) => s"_cluster/health/$value"
        case None => "_cluster/health"
      },
      Map("timeout" -> "2s").asJava
    ))
  }

  private def createStateRequest(indices: Seq[String]) = {
    new HttpGet(client.from(
      indices.toList match {
        case Nil => "_cluster/state/_all"
        case names => s"_cluster/state/_all/${names.mkString(",")}"
      }
    ))
  }
}
