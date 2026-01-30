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
package tech.beshu.ror.accesscontrol

import cats.data.{NonEmptyList, WriterT}
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControlList.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.History.BlockHistory
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, Header}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.collection.immutable.ListMap

class EnabledAccessControlList(val blocks: NonEmptyList[Block],
                               override val staticContext: AccessControlListStaticContext)
  extends AccessControlList {

  override val description: String = "Enabled ROR ACL"

  private type AclProcessingResult[B <: BlockContext] = Decision[(Block, B)]

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](context: RequestContext.Aux[B]): Task[(RegularRequestResult[B], History[B])] = {
    blocks
      .tail
      .foldLeft(checkBlock(blocks.head, context)) { case (currentResult, block) =>
        for {
          prevBlocksExecutionResult <- currentResult
          newCurrentResult <- prevBlocksExecutionResult match {
            case Decision.Denied(_) =>
              checkBlock(block, context)
            case Decision.Permitted(_) =>
              lift(prevBlocksExecutionResult)
          }
        } yield newCurrentResult
      }
      .run
      .map { case (blocksHistory, aclProcessingResult) =>
        val handlingResult: RegularRequestResult[B] = aclProcessingResult match {
          case Decision.Permitted((block, blockContext)) =>
            block.policy match {
              case Policy.Allow => RegularRequestResult.Allow(blockContext, block)
              case Policy.Forbid(_) => RegularRequestResult.ForbiddenBy(blockContext, block)
            }
          case Decision.Denied(_) if wasDeniedDueToAliasNotFound(blocksHistory) =>
            RegularRequestResult.AliasNotFound()
          case Decision.Denied(_) if wasDeniedDueToTemplateNotFound(blocksHistory) =>
            RegularRequestResult.TemplateNotFound()
          case Decision.Denied(_) =>
            wasDeniedDueToIndexNotFound(blocksHistory) match {
              case Some(error) =>
                RegularRequestResult.IndexNotFound(error.allowedClusters)
              case None =>
                RegularRequestResult.ForbiddenByMismatched(denyCausesPerBlockFrom(blocksHistory))
            }
        }
        handlingResult -> History(blocksHistory)
      }
      .onErrorHandle { ex =>
        RegularRequestResult.Failed(ex) -> History.empty
      }
  }

  override def handleMetadataRequest(context: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]): Task[(UserMetadataRequestResult, History[CurrentUserMetadataRequestBlockContext])] = {
    Task
      .parSequence(blocks.toList.map(executeBlocksForUserMetadata(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val blocksHistory = blockResults.map { case (_, blockHistory) => blockHistory }
        val handlingResult = matchedAllowedBlocks(blocksHistory) match {
          case Right(matchedResults) =>
            userMetadataFrom(matchedResults, context.initialBlockContext.userMetadata.currentGroupId) match {
              case Some((userMetadata, matchedBlock)) => UserMetadataRequestResult.Allow(userMetadata, matchedBlock)
              case None => createForbiddenResult(blocksHistory)
            }
          case Left(()) => createForbiddenResult(blocksHistory)
        }
        handlingResult -> History(blocksHistory.toVector)
      }
  }

  private def matchedAllowedBlocks[B <: BlockContext](blockResults: Iterable[BlockHistory[B]]) = {
    NonEmptyList.fromList {
      findAllMatchedBlocksWithAllowPolicyPrecedingFirstMatchedForbidPolicyBlock(blockResults)
    } match {
      case Some(allowedPolicyMatchedBlocks) => Right(allowedPolicyMatchedBlocks)
      case None => Left(())
    }
  }

  private def userMetadataFrom(permittedBlocks: NonEmptyList[BlockHistory.Permitted[CurrentUserMetadataRequestBlockContext]],
                               optPreferredGroupId: Option[GroupId]): Option[(UserMetadata, Block)] = {
    optPreferredGroupId match {
      case Some(preferredGroupId) =>
        val matchingPreferredGroupResults = permittedBlocks
          .toList
          .flatMap { case result@BlockHistory.Permitted(_, decision, _) =>
            decision.context
              .userMetadata.availableGroups
              .find(_.id == preferredGroupId)
              .map(preferredGroup => (result, preferredGroup))
          }
        matchingPreferredGroupResults
          .find { case (permitted, preferredGroup) => permitted.decision.context.userMetadata.kibanaIndex.isDefined }
          .orElse {
            matchingPreferredGroupResults.headOption
          }
          .map { case (matched, preferredGroup) =>
            val userMetadata =
              updateUserMetadataGroups(matched.decision.context, Some(preferredGroup), allAvailableGroupsFrom(permittedBlocks))
            (userMetadata, matched.block)
          }
      case None =>
        Some {
          val BlockHistory.Permitted(block, decision, _) = permittedBlocks.toList
            .find {
              _.decision.context.userMetadata.kibanaIndex.isDefined
            }
            .getOrElse {
              permittedBlocks.head
            }
          val userMetadata = updateUserMetadataGroups(decision.context, None, allAvailableGroupsFrom(permittedBlocks))
          (userMetadata, block)
        }
    }
  }

  private def createForbiddenResult(blockResults: List[BlockHistory[CurrentUserMetadataRequestBlockContext]]) = {
    val matchedForbidBlock = blockResults.collectFirstSome {
      case m@BlockHistory.Permitted(block, _, _) => block.policy match {
        case Policy.Allow => None
        case Policy.Forbid(_) => Some(m)
      }
      case BlockHistory.Denied(_, _, _) => None
    }
    matchedForbidBlock match {
      case Some(BlockHistory.Permitted(block, decision, _)) => UserMetadataRequestResult.ForbiddenBy(decision.context, block)
      case None => UserMetadataRequestResult.ForbiddenByMismatched(denyCausesPerBlockFrom(blockResults))
    }
  }

  private def allAvailableGroupsFrom(matchedResults: NonEmptyList[BlockHistory.Permitted[CurrentUserMetadataRequestBlockContext]]) = {
    UniqueList.from(
      matchedResults.toList.flatMap {
        case BlockHistory.Permitted(_, decision, _) => decision.context.userMetadata.availableGroups
      }
    )
  }

  private def updateUserMetadataGroups(blockContext: CurrentUserMetadataRequestBlockContext,
                                       currentGroup: Option[Group],
                                       availableGroups: UniqueList[Group]) = {
    currentGroup
      .foldLeft(blockContext.userMetadata.withAvailableGroups(availableGroups)) {
        case (userMetadata, group) => userMetadata.withCurrentGroup(group)
      }
  }

  private def executeBlocksForUserMetadata(block: Block,
                                           context: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]) = {
    block
      .evaluate(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def findAllMatchedBlocksWithAllowPolicyPrecedingFirstMatchedForbidPolicyBlock[B <: BlockContext](blocks: Iterable[BlockHistory[B]]): List[BlockHistory.Permitted[B]] = {
    blocks.view
      .collect { case b@BlockHistory.Permitted(_, _, _) => b }
      .takeWhile {
        _.block.policy match {
          case Policy.Allow => true
          case Policy.Forbid(_) => false
        }
      }
      .toList
  }

  private def checkBlock[B <: BlockContext : BlockContextUpdater](block: Block,
                                                                  requestContext: RequestContext.Aux[B]): WriterT[Task, Vector[BlockHistory[B]], AclProcessingResult[B]] = {
    for {
      blockEvalDecision <- WriterT.liftF(block.evaluate(requestContext))
      (decision, history) = blockEvalDecision
      aclProcessingResult <- lift(decision.map(block -> _)).tell(Vector(history))
    } yield aclProcessingResult
  }

  private def lift[B <: BlockContext](result: AclProcessingResult[B]): WriterT[Task, Vector[BlockHistory[B]], AclProcessingResult[B]] = {
    WriterT.value[Task, Vector[BlockHistory[B]], AclProcessingResult[B]](result)
  }

  private def denyCausesPerBlockFrom(history: Iterable[BlockHistory[_]]): ListMap[Block.Name, Denied.Cause] = {
    ListMap.from {
      history.flatMap {
        case BlockHistory.Permitted(_, _, _) => None
        case BlockHistory.Denied(block, decision, _) => Some(block.name -> decision.cause)
      }
    }
  }

  private def wasDeniedDueToIndexNotFound[B <: BlockContext](history: Iterable[BlockHistory[B]]): Option[Denied.Cause.IndexNotFound] = {
    val causes = denialCausesFrom(history)
    if (impersonationRelatedCauseExists(causes)) {
      None
    } else {
      indexNotFoundCauseExists(causes)
    }
  }

  private def wasDeniedDueToAliasNotFound[B <: BlockContext](history: Iterable[BlockHistory[B]]) = {
    val causes = denialCausesFrom(history)
    !impersonationRelatedCauseExists(causes) && aliasNotFoundCauseExists(causes)
  }

  private def wasDeniedDueToTemplateNotFound[B <: BlockContext](history: Iterable[BlockHistory[B]]) = {
    val causes = denialCausesFrom(history)
    !impersonationRelatedCauseExists(causes) && templateNotFoundCauseExists(causes)
  }

  private def indexNotFoundCauseExists(causes: Set[Denied.Cause]): Option[Denied.Cause.IndexNotFound] = {
    causes.collectFirst {
      case cause@Denied.Cause.IndexNotFound(_) => cause
    }
  }

  private def aliasNotFoundCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.AliasNotFound => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def templateNotFoundCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.TemplateNotFound => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def impersonationRelatedCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.ImpersonationNotAllowed => true
      case Denied.Cause.ImpersonationNotSupported => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def denialCausesFrom[B <: BlockContext](history: Iterable[BlockHistory[B]]): Set[Denied.Cause] = {
    history
      .flatMap {
        case BlockHistory.Permitted(_, _, _) =>
          Iterable.empty
        case BlockHistory.Denied(_, _, history) =>
          history.flatMap {
            _.decision match {
              case Decision.Permitted(_) => None
              case Denied(cause) => Some(cause)
            }
          }
      }
      .toCovariantSet
  }
}

object EnabledAccessControlList {

  final class AccessControlListStaticContext(blocks: NonEmptyList[Block],
                                             globalSettings: GlobalSettings,
                                             override val obfuscatedHeaders: Set[Header.Name])
    extends AccessControlStaticContext {

    override val forbiddenRequestMessage: String = globalSettings.forbiddenRequestMessage

    val usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine] = {
      blocks
        .flatMap(_.rules)
        .collect {
          case rule: FieldsRule => rule.settings.flsEngine
        }
        .headOption
    }

    val doesRequirePassword: Boolean = {
      globalSettings.showBasicAuthPrompt &&
        blocks
          .find(_
            .rules
            .collect {
              case _: AuthenticationRule => true
              case _: AuthorizationRule => true
            }
            .nonEmpty
          )
          .isDefined
    }
  }
}