package tech.beshu.ror.es.rradmin.rest

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestController, RestHandler, RestRequest}
import tech.beshu.ror.Constants
import tech.beshu.ror.adminapi._
import tech.beshu.ror.es.rradmin.{RRAdminAction, RRAdminRequest, RRAdminResponse}
import tech.beshu.ror.es.rradmin.{RRAdminRequest, RRAdminResponse}

@Inject
class RestRRAdminAction(settings: Settings, controller: RestController)
  extends BaseRestHandler(settings) with RestHandler {

  register("POST", AdminRestApi.forceReloadRorPath.endpointString)
  register("GET", AdminRestApi.provideRorIndexConfigPath.endpointString)
  register("POST", AdminRestApi.updateIndexConfigurationPath.endpointString)
  register("GET", AdminRestApi.provideRorFileConfigPath.endpointString)
  register("GET", Constants.REST_METADATA_PATH)
  
  override val getName: String = "ror-admin-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    client.execute(new RRAdminAction, new RRAdminRequest(request), new RestToXContentListener[RRAdminResponse](channel))
  }
  
  private def register(method: String, path: String): Unit = {
    controller.registerHandler(RestRequest.Method.valueOf(method), path, this)
  }
}
