package tech.beshu.ror.es.request.usermetadata

import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.UserMetadata

import scala.collection.JavaConverters._

class CurrentUserMetadataResponseActionListener(baseListener: ActionListener[ActionResponse],
                                                userMetadata: UserMetadata)
  extends ActionListener[ActionResponse] {

  override def onResponse(response: ActionResponse): Unit = baseListener.onResponse(new RRMetadataResponse(userMetadata))

  override def onFailure(e: Exception): Unit = baseListener.onFailure(e)

}

private class RRMetadataResponse(userMetadata: UserMetadata)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val sourceMap: Map[String, _] =
      userMetadata.loggedUser.map(u => (Constants.HEADER_USER_ROR, u.id.value.value)).toMap ++
        userMetadata.currentGroup.map(g => (Constants.HEADER_GROUP_CURRENT, g.value.value)).toMap ++
        userMetadata.foundKibanaIndex.map(i => (Constants.HEADER_KIBANA_INDEX, i.value.value)).toMap +
        (Constants.HEADER_GROUPS_AVAILABLE -> userMetadata.availableGroups.map(_.value.value).toList.toArray) ++
        stringifyKibanaAppsFrom(userMetadata).map(Constants.HEADER_KIBANA_HIDDEN_APPS -> _).toMap
    builder.map(sourceMap.asJava)
    builder
  }

  private def stringifyKibanaAppsFrom(userMetadata: UserMetadata) =
    if (userMetadata.hiddenKibanaApps.isEmpty) None
    else Some(userMetadata.hiddenKibanaApps.map(_.value.value).mkString(","))
}

