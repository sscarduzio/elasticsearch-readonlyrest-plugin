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

package tech.beshu.ror.es.rradmin.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import tech.beshu.ror.Constants;
import tech.beshu.ror.adminapi.AdminRestApi$;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.RRAdminRequest;

/**
 * Created by sscarduzio on 21/03/2017.
 */
public class RestRRAdminAction extends BaseRestHandler implements RestHandler {

  @Inject
  public RestRRAdminAction(Settings settings, RestController controller) {
    super(settings);
    controller.registerHandler(RestRequest.Method.valueOf("POST"), AdminRestApi$.MODULE$.forceReloadRorPath().endpointString(), this);
    controller.registerHandler(RestRequest.Method.valueOf("GET"), AdminRestApi$.MODULE$.provideRorIndexConfigPath().endpointString(), this);
    controller.registerHandler(RestRequest.Method.valueOf("POST"), AdminRestApi$.MODULE$.updateIndexConfigurationPath().endpointString(), this);
    controller.registerHandler(RestRequest.Method.valueOf("GET"), AdminRestApi$.MODULE$.provideRorFileConfigPath().endpointString(), this);
    controller.registerHandler(RestRequest.Method.valueOf("GET"), Constants.REST_METADATA_PATH, this);
  }
  public String getName() {
    return "ror-admin-handler";
  }

  @Override
  protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
    return (channel) -> client.execute(
        new RRAdminAction(),
        new RRAdminRequest(request),
        new RestToXContentListener<>(channel)
    );
  }
}
