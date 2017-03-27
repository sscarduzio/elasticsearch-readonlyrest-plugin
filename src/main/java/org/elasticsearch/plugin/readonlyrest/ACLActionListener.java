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

package org.elasticsearch.plugin.readonlyrest;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;

/**
 * Created by sscarduzio on 24/03/2017.
 */

class ACLActionListener implements ActionListener<ActionResponse> {
  private final Logger logger = Loggers.getLogger(getClass());

  private final ActionListener<ActionResponse> baseListener;
  private final ActionRequest request;
  private final RequestContext rc;
  private final BlockExitResult result;

  ACLActionListener(ActionRequest request,
                    ActionListener<ActionResponse> baseListener,
                    RequestContext rc, BlockExitResult result) {
    this.request = request;
    this.baseListener = baseListener;
    this.rc = rc;
    this.result = result;
  }

  public void onResponse(ActionResponse response) {
    boolean shouldContinue = true;
    for (Rule r : result.getBlock().getSyncRules()) {
      try {
        // Don't continue with further handlers if at least one says we should not continue
        shouldContinue &= r.onResponse(rc, request, response);
      } catch (Exception e) {
        logger.error(r.getKey() + " errored handling response: " + response);
        e.printStackTrace();
      }
    }
    if (shouldContinue) {
      baseListener.onResponse(response);
    }
  }

  public void onFailure(Exception e) {
    logger.info("INTERCEPTED FAILURE: " + e.getMessage());

    if (e instanceof ResourceAlreadyExistsException) {
      baseListener.onFailure(new ResourceAlreadyExistsException(".kibana"));
      return;
    }
    baseListener.onFailure(e);

  }
}
