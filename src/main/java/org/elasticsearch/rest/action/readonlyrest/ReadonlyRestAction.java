package org.elasticsearch.rest.action.readonlyrest;

import static org.elasticsearch.rest.RestRequest.Method.GET;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.StringRestResponse;

public class ReadonlyRestAction extends BaseRestHandler {

    @Inject public ReadonlyRestAction(Settings settings, Client client, RestController controller) {
        super(settings, client);

        controller.registerFilter(new RestFilter() {
					
					@Override
					public void process(RestRequest request, RestChannel channel, RestFilterChain filterChain) {
						if(!request.method().equals(GET) || request.content().length() > 0 || request.rawPath().contains("bar_me_pls")){
							channel.sendResponse(new StringRestResponse(RestStatus.FORBIDDEN, "barred request"));
							return;
						}
						filterChain.continueProcessing(request, channel);
					}
				});

    }

    public void handleRequest(final RestRequest request, final RestChannel channel) {}
}
