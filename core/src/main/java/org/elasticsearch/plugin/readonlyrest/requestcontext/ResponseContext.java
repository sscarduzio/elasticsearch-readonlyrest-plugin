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
package org.elasticsearch.plugin.readonlyrest.requestcontext;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class ResponseContext {

  private static SerializationTool serTool;
  private final FinalState finalState;
  private final Long durationMillis;
  private final RequestContext rc;
  private final Throwable error;
  private final BlockExitResult result;

  public ResponseContext(FinalState finalState, RequestContext rc, Throwable error, BlockExitResult result) {
    if (serTool == null) {
      serTool = new SerializationTool();
    }
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

  public String toJson() throws JsonProcessingException {
    return serTool.toJson(this);
  }

  public enum FinalState {
    FORBIDDEN,
    ALLOWED,
    ERRORED,
    NOT_FOUND
  }
}
