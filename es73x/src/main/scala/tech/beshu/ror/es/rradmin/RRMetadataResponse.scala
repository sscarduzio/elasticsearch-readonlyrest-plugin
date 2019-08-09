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
package tech.beshu.ror.es.rradmin

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.blocks.BlockContext
import scala.collection.JavaConverters._

class RRMetadataResponse(blockContext: BlockContext)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, _] =
      blockContext.responseHeaders.map(h => (h.name.value.value, h.value.value)).toMap ++
        blockContext.loggedUser.map(u => (Constants.HEADER_USER_ROR, u.id.value.value)).toMap ++
        blockContext.currentGroup.map(g => (Constants.HEADER_GROUP_CURRENT, g.value.value)).toMap ++
        blockContext.kibanaIndex.map(i => (Constants.HEADER_KIBANA_INDEX, i.value.value)).toMap ++
        NonEmptyList.fromList(blockContext.availableGroups.toList).map(groups => (Constants.HEADER_GROUPS_AVAILABLE, groups.map(_.value.value).toList.toArray)).toMap ++
        hiddenAppString.map(apps => (Constants.HEADER_KIBANA_HIDDEN_APPS, apps.split(",")))
    builder.map(sourceMap.asJava)
    builder
  }

  private def hiddenAppString = {
    blockContext
      .responseHeaders
      .find(_.name.value.value.toLowerCase == Constants.HEADER_KIBANA_HIDDEN_APPS)
      .map(_.value.value)
  }
}
