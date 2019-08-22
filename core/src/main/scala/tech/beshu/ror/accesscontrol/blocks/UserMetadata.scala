package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.domain.{Group, IndexName, LoggedUser}

import scala.collection.SortedSet

final case class UserMetadata(loggedUser: Option[LoggedUser],
                              currentGroup: Group,
                              availableGroups: SortedSet[Group],
                              foundKibanaIndex: Option[IndexName])
