package org.elasticsearch.plugin.readonlyrest.requestcontext;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class ResponseContext {

  private static final SerializationTool serTool = new SerializationTool();
  private final FinalState finalState;
  private final Long durationMillis;
  private final RequestContext rc;
  private final Throwable error;
  private final BlockExitResult result;

  public ResponseContext(FinalState finalState, RequestContext rc, Throwable error, BlockExitResult result) {
    this.finalState = finalState;
    this.durationMillis = System.currentTimeMillis() - rc.getTimestamp().getTime();
    this.rc = rc;
    this.result = result;
    this.error = error;
  }

  public Long getDurationMillis() {
    return durationMillis;
  }

  public Throwable getError() {
    return error;
  }

  public BlockExitResult getResult() {
    return result;
  }

  public RequestContext getRequestContext() {
    return rc;
  }

  public FinalState finalState() {
    return finalState;
  }

  public enum FinalState {
    FORBIDDEN,
    ALLOWED,
    ERRORED,
    NOT_FOUND
  }

  public String toJson() throws JsonProcessingException {
    return serTool.toJson(this);
  }
}
