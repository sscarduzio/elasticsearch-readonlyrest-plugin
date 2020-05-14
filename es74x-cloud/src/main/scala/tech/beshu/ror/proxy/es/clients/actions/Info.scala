package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.main.{MainResponse => ActionMainReponse}
import org.elasticsearch.client.core.MainResponse
import org.elasticsearch.cluster.ClusterName
import org.elasticsearch.{Build, Version}

object Info {

  implicit class MainResponseOps(val response: MainResponse) extends AnyVal {
    def toMainResponse: ActionMainReponse = new ActionMainReponse(
      response.getNodeName,
      Version.CURRENT,
      new ClusterName(response.getClusterName),
      response.getClusterUuid,
      Build.CURRENT
    )
  }
}
