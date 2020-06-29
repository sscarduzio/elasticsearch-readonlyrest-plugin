package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.{HttpGet, HttpPost, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.httpclient.{HttpGetWithEntity, RestClient}
import tech.beshu.ror.utils.misc.Version

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

  def reroute(command: JSON, commands: JSON*): JsonResponse = {
    call(createRerouteRequest(command :: commands.toList), new JsonResponse(_))
  }

  def configureRemoteClusters(remoteClusters: Map[String, List[String]]): SimpleResponse = {
    call(createAddCLusterSettingsRequest(remoteClusters), new SimpleResponse(_))
  }

  def cancelAllTasks(): JsonResponse = {
    call(createCancelAllTasksRequest(), new JsonResponse(_))
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

  private def createRerouteRequest(commands: List[JSON]) = {
    val request = new HttpPost(client.from("_cluster/reroute"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      s"""{
         |"commands":[
         |  ${commands.map(ujson.write(_)).mkString(",")}
         |]
         |}""".stripMargin
    ))
    request
  }

  private def createCancelAllTasksRequest() = {
    new HttpPost(client.from("_tasks/_cancel"))
  }

  private def createAddCLusterSettingsRequest(remoteClusters: Map[String, List[String]]) = {
    val remoteClustersConfigString = remoteClusters
      .map { case (name, seeds) =>
        s""""$name": { "seeds": [ ${seeds.mkString(",")} ] }"""
      }
      .mkString(",\n")

    val request = new HttpPut(client.from("_cluster/settings"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      if (Version.greaterOrEqualThan(esVersion, 6, 5, 0)) {
        s"""
           |{
           |  "persistent": {
           |    "cluster": {
           |      "remote": {
           |        $remoteClustersConfigString
           |      }
           |    }
           |  }
           |}
          """.stripMargin
      } else {
        s"""
           |{
           |  "persistent": {
           |    "search": {
           |      "remote": {
           |        $remoteClustersConfigString
           |      }
           |    }
           |  }
           |}
          """.stripMargin
      }
    ))
    request
  }
}
