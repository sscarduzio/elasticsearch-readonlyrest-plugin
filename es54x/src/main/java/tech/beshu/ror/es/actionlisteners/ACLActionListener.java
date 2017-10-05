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

package tech.beshu.ror.es.actionlisteners;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ACLActionListener implements ActionListener<ActionResponse> {

  private final LoggerShim logger;
  private final ActionListener<ActionResponse> baseListener;
  private final ActionRequest request;
  private final RequestContext rc;
  private final ACL acl;
  private final Object blockExitResult;

  public ACLActionListener(ActionRequest request,
                           ActionListener<ActionResponse> baseListener,
                           RequestContext rc,
                           Object blockExitResult,
                           ESContext context,
                           ACL acl
  ) {
    this.logger = context.logger(getClass());
    this.request = request;
    this.baseListener = baseListener;
    this.rc = rc;
    this.blockExitResult = blockExitResult;
    this.acl = acl;
  }

  public void onResponse(ActionResponse response) {

    if(acl.responseOkHook(rc, blockExitResult, response)) {
      baseListener.onResponse(response);
    }
    // #TODO check if to run onFailure?
  }

  public void onFailure(Exception e) {

    // #TODO are we interested in this?
//    boolean shouldContinue = true;
//
//    for (Rule r : result.getBlock().getRules()) {
//      try {
//        // Don't continue with further handlers if at least one says we should not continue
//        shouldContinue &= ruleActionListenersProvider.getActionListenerOf(r)
//          .map(al -> al.onFailure(r, result, rc, request, e))
//          .orElse(true);
//      } catch (Exception e1) {
//        logger.error(r.getKey() + " errored handling failure: " + e1);
//        e.printStackTrace();
//      }
//    }
//    if (shouldContinue) {
//      if (e != null) {
//        baseListener.onFailure(e);
//      }
//    }
    baseListener.onFailure(e);

  }
}
