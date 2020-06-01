/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.rest

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.rest.RestRequest.Method.{DELETE, GET, POST, PUT}
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestRequest}
import tech.beshu.ror.proxy.es.ProxyRestControllerDecorator
import tech.beshu.ror.proxy.es.clients.EsRestNodeClient

// todo: improve this class (we'd like to use wildcard in path instead of full path)
class RestGenericRequestAction(controller: ProxyRestControllerDecorator,
                               client: EsRestNodeClient)
  extends BaseRestHandler {

  controller.setGenericRequestAction(this)

  // xpack
  controller.registerHandler(GET, "/_xpack", this)
  controller.registerHandler(GET, "/_security/_authenticate", this)
  controller.registerHandler(POST, "/_security/delegate_pki", this)
  // apikey
  controller.registerHandler(GET, "/_security/api_key", this)
  controller.registerHandler(POST, "/_security/api_key", this)
  controller.registerHandler(PUT, "/_security/api_key", this)
  controller.registerHandler(DELETE, "/_security/api_key", this)
  // oauth2
  controller.registerHandler(POST, "/_security/oauth2/token", this)
  controller.registerHandler(DELETE, "/_security/oauth2/token", this)
  // oidc
  controller.registerHandler(POST, "/_security/oidc/authenticate", this)
  controller.registerHandler(POST, "/_security/oidc/logout", this)
  controller.registerHandler(POST, "/_security/oidc/prepare", this)
  // privilege
  controller.registerHandler(GET, "/_security/privilege/", this)
  controller.registerHandler(DELETE, "/_security/privilege/{application}/{privilege}", this)
  controller.registerHandler(GET, "/_security/privilege/_builtin", this)
  controller.registerHandler(GET, "/_security/privilege/{application}", this)
  controller.registerHandler(GET, "/_security/privilege/{application}/{privilege}", this)
  controller.registerHandler(PUT, "/_security/privilege/", this)
  controller.registerHandler(POST, "/_security/privilege/", this)
  // realm
  controller.registerHandler(POST, "/_security/realm/{realms}/_clear_cache", this)
  // role
  controller.registerHandler(POST, "/_security/role/{name}/_clear_cache", this)
  controller.registerHandler(DELETE, "/_security/role/{name}", this)
  controller.registerHandler(GET, "/_security/role/", this)
  controller.registerHandler(GET, "/_security/role/{name}", this)
  controller.registerHandler(POST, "/_security/role/{name}", this)
  controller.registerHandler(PUT, "/_security/role/{name}", this)
  // rolemapping
  controller.registerHandler(DELETE, "/_security/role_mapping/{name}", this)
  controller.registerHandler(GET, "/_security/role_mapping/", this)
  controller.registerHandler(GET, "/_security/role_mapping/{name}", this)
  controller.registerHandler(POST, "/_security/role_mapping/{name}", this)
  controller.registerHandler(PUT, "/_security/role_mapping/{name}", this)
  // saml
  controller.registerHandler(POST, "/_security/saml/authenticate", this)
  controller.registerHandler(POST, "/_security/saml/invalidate", this)
  controller.registerHandler(POST, "/_security/saml/logout", this)
  controller.registerHandler(POST, "/_security/saml/prepare", this)
  // user
  controller.registerHandler(POST, "/_security/user/{username}/_password", this)
  controller.registerHandler(PUT, "/_security/user/{username}/_password", this)
  controller.registerHandler(POST, "/_security/user/_password", this)
  controller.registerHandler(PUT, "/_security/user/_password", this)
  controller.registerHandler(DELETE, "/_security/user/{username}", this)
  controller.registerHandler(GET, "/_security/user/_privileges", this)
  controller.registerHandler(GET, "/_security/user/", this)
  controller.registerHandler(GET, "/_security/user/{username}", this)
  controller.registerHandler(GET, "/_security/user/{username}/_has_privileges", this)
  controller.registerHandler(POST, "/_security/user/{username}/_has_privileges", this)
  controller.registerHandler(GET, "/_security/user/_has_privileges", this)
  controller.registerHandler(POST, "/_security/user/_has_privileges", this)
  controller.registerHandler(POST, "/_security/user/{username}", this)
  controller.registerHandler(PUT, "/_security/user/{username}", this)
  controller.registerHandler(POST, "/_security/user/{username}/_enable", this)
  controller.registerHandler(PUT, "/_security/user/{username}/_enable", this)
  controller.registerHandler(POST, "/_security/user/{username}/_disable", this)
  controller.registerHandler(PUT, "/_security/user/{username}/_disable", this)

  override val getName: String = "generic_request_action"

  override def prepareRequest(request: RestRequest, client: NodeClient): BaseRestHandler.RestChannelConsumer = {
    channel: RestChannel => {
      client.doExecute(
        GenericAction.INSTANCE,
        new GenericRequest(request),
        new RestToXContentListener[GenericResponse](channel)
      )
    }
  }
}
