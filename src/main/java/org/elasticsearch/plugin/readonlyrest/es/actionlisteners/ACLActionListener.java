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

package org.elasticsearch.plugin.readonlyrest.es.actionlisteners;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ACLActionListener implements ActionListener<ActionResponse> {

  private final RuleActionListenersProvider ruleActionListenersProvider;
  private final Logger logger;
  private final ActionListener<ActionResponse> baseListener;
  private final ActionRequest request;
  private final RequestContext rc;
  private final BlockExitResult result;

  public ACLActionListener(ActionRequest request,
                           ActionListener<ActionResponse> baseListener,
                           RuleActionListenersProvider ruleActionListenersProvider,
                           RequestContext rc,
                           BlockExitResult result,
                           ESContext context) {
    this.ruleActionListenersProvider = ruleActionListenersProvider;
    this.logger = context.logger(getClass());
    this.request = request;
    this.baseListener = baseListener;
    this.rc = rc;
    this.result = result;
  }

  public void onResponse(ActionResponse response) {
    boolean shouldContinue = true;
    for (Rule r : result.getBlock().getRules()) {
      try {
        // Don't continue with further handlers if at least one says we should not continue
        shouldContinue &= ruleActionListenersProvider.getActionListenerOf(r)
            .map(al -> al.onResponse(r, result, rc, request, response))
            .orElse(true);
      } catch (Exception e) {
        logger.error(r.getKey() + " error handling response: " + response);
        e.printStackTrace();
      }
    }
    if (shouldContinue) {
      baseListener.onResponse(response);
    }
  }

  public void onFailure(Exception e) {
    boolean shouldContinue = true;

    for (Rule r : result.getBlock().getRules()) {
      try {
        // Don't continue with further handlers if at least one says we should not continue
        shouldContinue &= ruleActionListenersProvider.getActionListenerOf(r)
            .map(al -> al.onFailure(r, result, rc, request, e))
            .orElse(true);
      } catch (Exception e1) {
        logger.error(r.getKey() + " errored handling failure: " + e1);
        e.printStackTrace();
      }
    }
    if (shouldContinue) {
      baseListener.onFailure(e);
    }

  }
}
