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
