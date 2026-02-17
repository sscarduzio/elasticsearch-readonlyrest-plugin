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

import cats.data.{NonEmptyList, NonEmptySet}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControlList.{AccessControlStaticContext, RegularRequestResult, UserMetadataRequestResult}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.syntax.*

import scala.collection.immutable.ListMap

trait AccessControlList {
  def description: String

  def handleRegularRequest[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): Task[(RegularRequestResult[B], History[B])]

  def handleMetadataRequest(requestContext: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[(UserMetadataRequestResult, History[UserMetadataRequestBlockContext])]

  def staticContext: AccessControlStaticContext
}

object AccessControlList {

  sealed trait RegularRequestResult[B <: BlockContext]
  object RegularRequestResult {
    final case class Allowed[B <: BlockContext](matchedBlockContext: B)
      extends RegularRequestResult[B]
    final case class Forbidden[B <: BlockContext](matchedBlockContext: B)
      extends RegularRequestResult[B]
    final case class ForbiddenByMismatched[B <: BlockContext](detailedCauses: ListMap[Block.Name, Denied.Cause])
      extends RegularRequestResult[B] {

      lazy val causes: NonEmptySet[ForbiddenCause] = detailedToGenericCauses(detailedCauses.values)
    }
    final case class IndexNotFound[B <: BlockContext](allowedClusters: Set[ClusterName.Full])
      extends RegularRequestResult[B]
    final case class AliasNotFound[B <: BlockContext]()
      extends RegularRequestResult[B]
    final case class TemplateNotFound[B <: BlockContext]()
      extends RegularRequestResult[B]
    final case class Failed[B <: BlockContext](ex: Throwable)
      extends RegularRequestResult[B]
    final case class PassedThrough[B <: BlockContext]()
      extends RegularRequestResult[B]
  }

  sealed trait UserMetadataRequestResult
  object UserMetadataRequestResult {
    final case class Allowed(userMetadata: UserMetadata) extends UserMetadataRequestResult
    final case class Forbidden(matchedBlockContext: UserMetadataRequestBlockContext) extends UserMetadataRequestResult
    final case class ForbiddenByMismatched(detailedCauses: ListMap[Block.Name, Denied.Cause])
      extends UserMetadataRequestResult {

      lazy val causes: NonEmptySet[ForbiddenCause] = detailedToGenericCauses(detailedCauses.values)
    }
    case object PassedThrough extends UserMetadataRequestResult
    case object RorKbnPluginNotSupported extends UserMetadataRequestResult
  }

  sealed trait ForbiddenCause
  object ForbiddenCause {
    case object OperationNotAllowed extends ForbiddenCause
    case object ImpersonationNotSupported extends ForbiddenCause
    case object ImpersonationNotAllowed extends ForbiddenCause

    def from(cause: Denied.Cause): ForbiddenCause = {
      cause match {
        case Denied.Cause.ImpersonationNotAllowed =>
          ForbiddenCause.ImpersonationNotAllowed
        case Denied.Cause.ImpersonationNotSupported =>
          ForbiddenCause.ImpersonationNotSupported
        case _: Denied.Cause.OtherFailure |
             _: Denied.Cause.AuthenticationFailure |
             _: Denied.Cause.AuthorizationFailure =>
          ForbiddenCause.OperationNotAllowed
      }
    }
  }

  trait AccessControlStaticContext {
    def usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine]

    def doesRequirePassword: Boolean

    def forbiddenRequestMessage: String

    def obfuscatedHeaders: Set[Header.Name]
  }

  private def detailedToGenericCauses(detailedCauses: Iterable[Denied.Cause]): NonEmptySet[ForbiddenCause] = {
    NonEmptyList
      .fromList(detailedCauses.map(ForbiddenCause.from).toList.distinct)
      .getOrElse(NonEmptyList.one(ForbiddenCause.OperationNotAllowed))
      .toNes
  }
}
