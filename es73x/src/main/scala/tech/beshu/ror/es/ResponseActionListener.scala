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
