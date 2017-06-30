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
package org.elasticsearch.plugin.readonlyrest.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.Constants;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.requestcontext.ResponseContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.ResponseContext.FinalState;

/**
 * Created by sscarduzio on 29/06/2017.
 */
public abstract class AuditSinkStub {

  protected static final Integer MAX_ITEMS = 100;
  protected static final Integer MAX_KB = 100;
  protected static final Integer MAX_SECONDS = 2;
  protected static final Integer MAX_RETRIES = 3;

  public abstract void submit(ResponseContext rc) throws JsonProcessingException;

  public abstract Boolean isAuditCollectorEnabled();

  public void log(ResponseContext res, Logger logger) {

    boolean skipLog = res.finalState().equals(FinalState.ALLOWED) &&
      Verbosity.INFO.equals(res.getResult().getBlock().getVerbosity());

    if (skipLog) {
      return;
    }
    String color;
    switch (res.finalState()) {

      case FORBIDDEN:
        color = Constants.ANSI_PURPLE;
        break;
      case ALLOWED:
        color = Constants.ANSI_CYAN;
        break;
      case ERRORED:

        color = Constants.ANSI_RED;
        break;
      case NOT_FOUND:

        color = Constants.ANSI_YELLOW;
        break;
      default:
        color = Constants.ANSI_WHITE;
    }
    logger.info(color + res.finalState().name() + " by " +
                  (res.getResult().isMatch() ? ("'" + res.getResult().toString() + "'") : "default") +
                  " req=" + res.getRequestContext() + " " + Constants.ANSI_RESET);

    if (!isAuditCollectorEnabled()) {
      return;
    }

    try {
      submit(res);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

  }
}
