package org.elasticsearch.plugin.readonlyrest.acl;

import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;

  public RequestContext(RestChannel channel, RestRequest request, String action) {

    this.channel = channel;
    this.request = request;
    this.action = action;
  }

  public RestChannel getChannel() {
    return channel;
  }

  public RestRequest getRequest() {
    return request;
  }

  public String getAction() {
    return action;
  }


}
