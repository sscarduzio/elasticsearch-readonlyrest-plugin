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

  public abstract void submit(ResponseContext rc) throws JsonProcessingException;

  protected static final Integer MAX_ITEMS = 100;
  protected static final Integer MAX_KB = 100;
  protected static final Integer MAX_SECONDS = 2;
  protected static final Integer MAX_RETRIES = 3;

  public void log(ResponseContext res, Logger logger) {

    boolean shouldLog = res.finalState().equals(FinalState.ALLOWED) &&
      Verbosity.INFO.equals(res.getResult().getBlock().getVerbosity());

    if (!shouldLog) {
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

    try {
      submit(res);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

  }
}
