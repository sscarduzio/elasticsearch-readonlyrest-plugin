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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

public abstract class Rule {
  protected final RuleExitResult MATCH;
  protected final RuleExitResult NO_MATCH;

  Rule() {
    MATCH = new RuleExitResult(true, this);
    NO_MATCH = new RuleExitResult(false, this);
  }

  public abstract String getKey();

  /**
   * This hook is only called if the result is a match.
   *
   * @param result   BlockExitResult rules block exit result
   * @param rc       RequestContext
   * @param ar       ActionRequest
   * @param response ActionResponse
   * @return should continue to process the handlers' pipeline
   */
  public boolean onResponse(BlockExitResult result, RequestContext rc, ActionRequest ar, ActionResponse response) {
    return true;
  }

  /**
   * This hook is called before throwing the exception e.
   * <p>
   * Either rethrow or throw a new exception.
   * #XXX DANGER: if you return false without throwing or writing to the channel, the connection will hang!
   *
   * @param result BlockExitResult rules block exit result
   * @param rc     RequestContext
   * @param ar     ActionRequest
   * @param e      The occurred exception
   * @return should continue to process the handlers' pipeline
   */
  public boolean onFailure(BlockExitResult result, RequestContext rc, ActionRequest ar, Throwable e) {
    return true;
  }

}
