package tech.beshu.ror.acl.blocks

import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.{Group, Header, IndexName, LoggedUser}
import tech.beshu.ror.acl.blocks.RequestContextInitiatedBlockContext.BlockContextData
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.request.RequestGroup.{AGroup, `N/A`}

trait BlockContext {

  def loggedUser: Option[LoggedUser]
  def withLoggedUser(user: LoggedUser): BlockContext

  def currentGroup: Option[Group]
  def withCurrentGroup(group: Group): BlockContext

  def availableGroups: Set[Group]
  def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext

  def responseHeaders: Set[Header]
  def withAddedResponseHeader(header: Header): BlockContext

  def contextHeaders: Set[Header]
  def withAddedContextHeader(header: Header): BlockContext

  def kibanaIndex: Option[IndexName]
  def withKibanaIndex(index: IndexName): BlockContext

  def indices: Set[IndexName]
  def withIndices(indices: NonEmptySet[IndexName]): BlockContext

  def repositories: Set[IndexName]
  def withRepositories(indices: NonEmptySet[IndexName]): BlockContext

  def snapshots: Set[IndexName]
  def withSnapshots(indices: NonEmptySet[IndexName]): BlockContext
}

class RequestContextInitiatedBlockContext private(val data: BlockContextData)
  extends BlockContext {

  override def loggedUser: Option[LoggedUser] = data.loggedUser

  override def withLoggedUser(user: LoggedUser): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(loggedUser = Some(user)))

  override def currentGroup: Option[Group] = data.currentGroup

  override def withCurrentGroup(group: Group): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(currentGroup = Some(group)))

  override def availableGroups: Set[Group] = data.availableGroups

  override def withAddedAvailableGroups(groups: NonEmptySet[Group]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(availableGroups = data.availableGroups ++ groups.toSortedSet))

  override def responseHeaders: Set[Header] = data.responseHeaders.toSet

  override def withAddedResponseHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(responseHeaders = data.responseHeaders :+ header))

  override def contextHeaders: Set[Header] = data.contextHeaders.toSet

  override def withAddedContextHeader(header: Header): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(contextHeaders = data.contextHeaders :+ header))

  override def kibanaIndex: Option[IndexName] = data.kibanaIndex

  override def withKibanaIndex(index: IndexName): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(kibanaIndex = Some(index)))

  override def indices: Set[IndexName] = data.indices

  override def withIndices(indices: NonEmptySet[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(indices = indices.toSortedSet))

  override def repositories: Set[IndexName] = data.repositories

  override def withRepositories(repositories: NonEmptySet[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(repositories = repositories.toSortedSet))

  override def snapshots: Set[IndexName] = data.snapshots

  override def withSnapshots(snapshots: NonEmptySet[IndexName]): BlockContext =
    new RequestContextInitiatedBlockContext(data.copy(snapshots = snapshots.toSortedSet))
}

object RequestContextInitiatedBlockContext {

  final case class BlockContextData(loggedUser: Option[LoggedUser],
                                    currentGroup: Option[Group],
                                    availableGroups: Set[Group],
                                    responseHeaders: Vector[Header],
                                    contextHeaders: Vector[Header],
                                    kibanaIndex: Option[IndexName],
                                    indices: Set[IndexName],
                                    repositories: Set[IndexName],
                                    snapshots: Set[IndexName])

  def fromRequestContext(requestContext: RequestContext): RequestContextInitiatedBlockContext =
    new RequestContextInitiatedBlockContext(
      BlockContextData(
        loggedUser = None,
        currentGroup = requestContext.currentGroup match {
          case AGroup(userGroup) => Some(userGroup)
          case `N/A` => None
        },
        availableGroups = Set.empty,
        responseHeaders = Vector.empty,
        contextHeaders = Vector.empty,
        kibanaIndex = None,
        indices = Set.empty,
        repositories = Set.empty,
        snapshots = Set.empty
      )
    )
}