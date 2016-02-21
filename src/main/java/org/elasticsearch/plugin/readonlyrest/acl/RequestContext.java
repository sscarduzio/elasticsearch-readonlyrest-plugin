package org.elasticsearch.plugin.readonlyrest.acl;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class RequestContext {
  private final RestChannel channel;
  private final RestRequest request;
  private final String action;
  private final ActionRequest actionRequest;

  public RequestContext(RestChannel channel, RestRequest request, String action, ActionRequest actionRequest) {

    this.channel = channel;
    this.request = request;
    this.action = action;
    this.actionRequest = actionRequest;
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

  public ActionRequest getActionRequest() {
    return actionRequest;
  }

}
