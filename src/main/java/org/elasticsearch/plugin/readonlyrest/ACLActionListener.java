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
