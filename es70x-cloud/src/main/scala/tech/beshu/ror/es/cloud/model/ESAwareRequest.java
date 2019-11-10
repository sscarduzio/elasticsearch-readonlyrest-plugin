package tech.beshu.ror.es.cloud.model;

import org.elasticsearch.action.ActionRequest;

public class ESAwareRequest {
  private final IncomingRequest ir;

  private final String action;

  public IncomingRequest getIncomingRequest() {
    return ir;
  }

  public String getAction() {
    return action;
  }

  public ActionRequest getActionRequest() {
    return ar;
  }

  private final ActionRequest ar;

  public ESAwareRequest(IncomingRequest ir, String action, ActionRequest ar){
    this.ir = ir;
    this.action=action;
    this.ar=ar;
  }
}
