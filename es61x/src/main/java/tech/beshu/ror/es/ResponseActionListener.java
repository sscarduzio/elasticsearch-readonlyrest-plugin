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

import com.google.common.base.Strings;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.es.rradmin.RRMetadataResponse;
import tech.beshu.ror.requestcontext.RequestContext;

public class ResponseActionListener implements ActionListener<ActionResponse> {
  private final String action;
  private final ActionListener<ActionResponse> baseListener;
  private final ActionRequest request;
  private final RequestContext requestContext;
  private final Logger logger;

  ResponseActionListener(String action, ActionRequest request,
      ActionListener<ActionResponse> baseListener,
      RequestContext requestContext, Logger logger) {
    this.action = action;
    this.logger = logger;
    this.request = request;
    this.baseListener = baseListener;
    this.requestContext = requestContext;
  }

  @Override
  public void onResponse(ActionResponse actionResponse) {
    String uri = requestContext.getUri();
    if(!Strings.isNullOrEmpty(uri) && uri.startsWith(Constants.REST_METADATA_PATH)) {
      baseListener.onResponse(new RRMetadataResponse(requestContext));
      return;
    }
    baseListener.onResponse(actionResponse);
  }

  @Override
  public void onFailure(Exception e) {
    baseListener.onFailure(e);
  }

}
