package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.blocks.BlockContext.{CurrentUserMetadataRequestBlockContext, GeneralIndexRequestBlockContext, GeneralNonIndexRequestBlockContext, RepositoryRequestBlockContext, SnapshotRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Header, IndexName, RepositoryName, SnapshotName}

sealed trait BlockContextUpdater[B <: BlockContext] {

  def emptyBlockContext(blockContext: B): B
  def withUserMetadata(blockContext: B, userMetadata: UserMetadata): B
  def withAddedResponseHeader(blockContext: B, header: Header): B
  def withAddedContextHeader(blockContext: B, header: Header): B
}

object BlockContextUpdater {

  def apply[B <: BlockContext](implicit instance: BlockContextUpdater[B]): BlockContextUpdater[B] = instance

  implicit object CurrentUserMetadataRequestBlockContextUpdater
    extends BlockContextUpdater[CurrentUserMetadataRequestBlockContext] {

    override def emptyBlockContext(blockContext: CurrentUserMetadataRequestBlockContext): CurrentUserMetadataRequestBlockContext =
      CurrentUserMetadataRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: CurrentUserMetadataRequestBlockContext,
                                  userMetadata: UserMetadata): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: CurrentUserMetadataRequestBlockContext,
                                         header: Header): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: CurrentUserMetadataRequestBlockContext,
                                        header: Header): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)
  }

  implicit object GeneralNonIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralNonIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralNonIndexRequestBlockContext): GeneralNonIndexRequestBlockContext =
      GeneralNonIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: GeneralNonIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralNonIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralNonIndexRequestBlockContext,
                                         header: Header): GeneralNonIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: GeneralNonIndexRequestBlockContext,
                                        header: Header): GeneralNonIndexRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)
  }

  implicit object RepositoryRequestBlockContextUpdater
    extends BlockContextUpdater[RepositoryRequestBlockContext] {

    override def emptyBlockContext(blockContext: RepositoryRequestBlockContext): RepositoryRequestBlockContext =
      RepositoryRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: RepositoryRequestBlockContext,
                                  userMetadata: UserMetadata): RepositoryRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: RepositoryRequestBlockContext,
                                         header: Header): RepositoryRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: RepositoryRequestBlockContext,
                                        header: Header): RepositoryRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)

    def withRepositories(blockContext: RepositoryRequestBlockContext,
                         repositories: Set[RepositoryName]): RepositoryRequestBlockContext =
      blockContext.copy(repositories = repositories)
  }

  implicit object SnapshotRequestBlockContextUpdater
    extends BlockContextUpdater[SnapshotRequestBlockContext] {
    override def emptyBlockContext(blockContext: SnapshotRequestBlockContext): SnapshotRequestBlockContext =
      SnapshotRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: SnapshotRequestBlockContext,
                                  userMetadata: UserMetadata): SnapshotRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: SnapshotRequestBlockContext,
                                         header: Header): SnapshotRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: SnapshotRequestBlockContext,
                                        header: Header): SnapshotRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)

    def withSnapshots(blockContext: SnapshotRequestBlockContext,
                      snapshots: Set[SnapshotName]): SnapshotRequestBlockContext =
      blockContext.copy(snapshots = snapshots)

    def withRepositories(blockContext: SnapshotRequestBlockContext,
                         repositories: Set[RepositoryName]): SnapshotRequestBlockContext =
      blockContext.copy(repositories = repositories)
  }

  implicit object TemplateRequestBlockContextUpdater
    extends BlockContextUpdater[TemplateRequestBlockContext] {

    override def emptyBlockContext(blockContext: TemplateRequestBlockContext): TemplateRequestBlockContext =
      TemplateRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: TemplateRequestBlockContext,
                                  userMetadata: UserMetadata): TemplateRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: TemplateRequestBlockContext,
                                         header: Header): TemplateRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: TemplateRequestBlockContext,
                                        header: Header): TemplateRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)
  }

  implicit object GeneralIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralIndexRequestBlockContext): GeneralIndexRequestBlockContext =
      GeneralIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: GeneralIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralIndexRequestBlockContext,
                                         header: Header): GeneralIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedContextHeader(blockContext: GeneralIndexRequestBlockContext,
                                        header: Header): GeneralIndexRequestBlockContext =
      blockContext.copy(contextHeaders = blockContext.contextHeaders + header)

    def withIndices(blockContext: GeneralIndexRequestBlockContext,
                    indices: Set[IndexName]): GeneralIndexRequestBlockContext =
      blockContext.copy(indices = indices)

  }
}


