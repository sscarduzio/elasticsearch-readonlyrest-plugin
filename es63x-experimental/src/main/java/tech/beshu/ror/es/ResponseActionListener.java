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

package tech.beshu.ror.es;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import tech.beshu.ror.acl.blocks.BlockContext;
import tech.beshu.ror.acl.request.RequestContext;
import tech.beshu.ror.es.rradmin.RRMetadataResponse;

public class ResponseActionListener implements ActionListener<ActionResponse> {
  private final ActionListener<ActionResponse> baseListener;
  private final RequestContext requestContext;
  private final BlockContext blockContext;

  ResponseActionListener(ActionListener<ActionResponse> baseListener, RequestContext requestContext,
      BlockContext blockContext) {
    this.baseListener = baseListener;
    this.requestContext = requestContext;
    this.blockContext = blockContext;
  }

  @Override
  public void onResponse(ActionResponse actionResponse) {
    if (requestContext.uriPath().isRestMetadataPath()) {
      baseListener.onResponse(new RRMetadataResponse(blockContext));
    } else {
      baseListener.onResponse(actionResponse);
    }
  }

  @Override
  public void onFailure(Exception e) {
    baseListener.onFailure(e);
  }

}
