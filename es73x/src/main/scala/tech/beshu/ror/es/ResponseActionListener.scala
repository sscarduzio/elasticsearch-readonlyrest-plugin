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
package tech.beshu.ror.es

import org.elasticsearch.action.{ActionListener, ActionResponse}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.es.rradmin.RRMetadataResponse

class ResponseActionListener(baseListener: ActionListener[ActionResponse],
                             requestContext: RequestContext,
                             blockContext: BlockContext)
  extends ActionListener[ActionResponse]{

  override def onResponse(response: ActionResponse): Unit = {
    if (requestContext.uriPath.isRestMetadataPath) baseListener.onResponse(new RRMetadataResponse(blockContext))
    else baseListener.onResponse(response)
  }

  override def onFailure(e: Exception): Unit = baseListener.onFailure(e)
}
