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
package tech.beshu.ror.commons.shims.es;

import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.RawSettings;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.ResponseContext;

import java.text.SimpleDateFormat;

/**
 * Created by sscarduzio on 29/06/2017.
 */
public abstract class AuditSinkCore {
  public static final String AUDIT_COLLECTOR = "audit_collector";
  protected final static SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

  protected static boolean isEnabled(RawSettings settings) {
    return settings.booleanOpt(AUDIT_COLLECTOR).orElse(false);
  }

  public abstract void submit(ResponseContext rep);

  public abstract Boolean isAuditCollectorEnabled();

  public void log(ResponseContext res, LoggerShim logger) {
    if (res == null) {
      logger.error("trying to log with null response context", new Throwable());
    }
    doLog(res, logger);
  }

  private void doLog(ResponseContext res, LoggerShim logger) {

    ResponseContext.FinalState fState = res.finalState();
    boolean skipLog = fState.equals(ResponseContext.FinalState.ALLOWED) &&
      !Verbosity.INFO.equals(res.getVerbosity());

    if (skipLog) {
      return;
    }
    String color;
    switch (fState) {
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
    StringBuilder sb = new StringBuilder();
    sb
      .append(color)
      .append(fState.name())
      .append(" by ")
      .append(res.getReason())
      .append(" req=")
      .append(res.getRequestContext())
      .append(" ")
      .append(Constants.ANSI_RESET);

    logger.info(sb.toString());

    if (!isAuditCollectorEnabled()) {
      return;
    }

    submit(res);

  }
}
