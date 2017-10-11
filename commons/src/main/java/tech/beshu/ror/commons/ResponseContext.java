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
package tech.beshu.ror.commons;

import tech.beshu.ror.commons.shims.request.RequestContextShim;

/**
 * Created by sscarduzio on 28/06/2017.
 */
public class ResponseContext {

  private final FinalState finalState;
  private final Long finishedHandling;
  private final RequestContextShim rc;
  private final Throwable error;
  private final Verbosity verbosity;
  private final String reason;
  private final boolean isMatch;

  public ResponseContext(FinalState finalState, RequestContextShim rc, Throwable error, Verbosity verbosity, String reason, boolean isMatch) {
    this.finalState = finalState;
    this.finishedHandling = System.currentTimeMillis();
    this.rc = rc;
    this.verbosity = verbosity;
    this.error = error;
    this.reason = reason;
    this.isMatch = isMatch;
  }

  public boolean getIsMatch() {
    return isMatch;
  }

  public String getReason() {
    return reason;
  }

  public Long getDurationMillis() {
    return finishedHandling - rc.getTimestamp().getTime();
  }

  public Throwable getError() {
    return error;
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }

  public RequestContextShim getRequestContext() {
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
}
