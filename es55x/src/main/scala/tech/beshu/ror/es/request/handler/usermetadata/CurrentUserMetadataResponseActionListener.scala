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
package tech.beshu.ror.es.request.handler.usermetadata

import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.accesscontrol.blocks.metadata.{MetadataValue, UserMetadata}
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.JavaConverters._

class CurrentUserMetadataResponseActionListener(requestContext: RequestContext,
                                                baseListener: ActionListener[ActionResponse],
                                                userMetadata: UserMetadata)
  extends ActionListener[ActionResponse] {

  override def onResponse(response: ActionResponse): Unit = {
    if(requestContext.uriPath.isCurrentUserMetadataPath)
      baseListener.onResponse(new RRMetadataResponse(userMetadata))
    else
      baseListener.onResponse(response)
  }

  override def onFailure(e: Exception): Unit = baseListener.onFailure(e)

}

private class RRMetadataResponse(userMetadata: UserMetadata)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, _] = MetadataValue.read(userMetadata).mapValues(MetadataValue.toAny)
    builder.map(sourceMap.asJava)
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()
}

