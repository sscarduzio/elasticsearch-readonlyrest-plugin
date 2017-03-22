package org.elasticsearch.plugin.readonlyrest.rradmin.rest;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.rradmin.RRAdminAction;
import org.elasticsearch.plugin.readonlyrest.rradmin.RRAdminRequest;
import org.elasticsearch.plugin.readonlyrest.rradmin.RRAdminResponse;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;

import java.io.IOException;

/**
 * Created by sscarduzio on 21/03/2017.
 */
public class RestRRAdminAction extends BaseRestHandler {
  @Inject
  public RestRRAdminAction(Settings settings, RestController controller) {
    super(settings);
    controller.registerHandler(RestRequest.Method.POST, "/_readonlyrest/admin/refreshconfig", this);
  }

  @Override
  protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
    return (channel) -> {
      client.execute(RRAdminAction.INSTANCE, new RRAdminRequest(), new RestToXContentListener<RRAdminResponse>(channel));
    };
  }
}
