package org.elasticsearch.plugin.readonlyrest.wiring;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;

/**
 * Created by sscarduzio on 30/03/2017.
 */
public class RRRestHandler implements RestHandler {
  @Override
  public void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
    ThreadRepo.channel.set(channel);
    handleRequest(request, channel, client);
  }
}