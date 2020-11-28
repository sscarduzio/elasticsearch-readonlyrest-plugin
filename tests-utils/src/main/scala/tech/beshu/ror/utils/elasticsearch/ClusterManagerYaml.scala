package tech.beshu.ror.utils.elasticsearch

import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.elasticsearch.BaseManager.YamlMapResponse
import tech.beshu.ror.utils.httpclient.RestClient
import scala.collection.JavaConverters._

class ClusterManagerYaml(client: RestClient,
                         esVersion: String)
  extends BaseManager(client)  {

  def health(): YamlMapResponse = health(None)

  private def health(index: Option[String]): YamlMapResponse = {
    call(createHealthRequest(index), new YamlMapResponse(_))
  }

  private def createHealthRequest(index: Option[String]) = {
    new HttpGet(client.from(
      index match {
        case Some(value) => s"_cluster/health/$value"
        case None => "_cluster/health"
      },
      Map("timeout" -> "2s", "format" -> "yaml").asJava
    ))
  }
}
