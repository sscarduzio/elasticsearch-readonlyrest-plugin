package tech.beshu.ror.es;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.es.rradmin.RRMetadataResponse;
import tech.beshu.ror.requestcontext.RequestContext;

public class ResponseActionListener implements ActionListener<ActionResponse> {
  private final String action;
  private final ActionListener<ActionResponse> baseListener;
  private final ActionRequest request;
  private final RequestContext requestContext;
  private final Logger logger;

  ResponseActionListener(String action, ActionRequest request,
      ActionListener<ActionResponse> baseListener,
      RequestContext requestContext, Logger logger) {
    this.action = action;
    this.logger = logger;
    this.request = request;
    this.baseListener = baseListener;
    this.requestContext = requestContext;
  }

  @Override
  public void onResponse(ActionResponse actionResponse) {
    if(Constants.REST_METADATA_PATH.equals(requestContext.getUri())) {
      baseListener.onResponse(new RRMetadataResponse(requestContext));
      return;
    }
    baseListener.onResponse(actionResponse);
  }

  @Override
  public void onFailure(Exception e) {
    baseListener.onFailure(e);
  }

}
