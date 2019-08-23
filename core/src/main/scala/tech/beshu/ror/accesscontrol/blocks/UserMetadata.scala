package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.domain.{Group, IndexName, KibanaApp, LoggedUser}

import scala.collection.SortedSet

final case class UserMetadata(loggedUser: Option[LoggedUser],
                              currentGroup: Option[Group],
                              availableGroups: SortedSet[Group],
                              foundKibanaIndex: Option[IndexName],
                              hiddenKibanaApps: Set[KibanaApp])
