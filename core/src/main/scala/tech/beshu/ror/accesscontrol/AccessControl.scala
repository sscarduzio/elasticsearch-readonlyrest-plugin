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

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControl.{AccessControlStaticContext, RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.blocks.Block.History
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RequestContext

trait AccessControl {
  def description: String
  def handleRegularRequest[B <: BlockContext : BlockContextUpdater](requestContext: RequestContext.Aux[B]): Task[WithHistory[RegularRequestResult[B], B]]
  def handleMetadataRequest(requestContext: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]): Task[WithHistory[UserMetadataRequestResult, CurrentUserMetadataRequestBlockContext]]
  def staticContext: AccessControlStaticContext
}

object AccessControl {

  final case class WithHistory[RESULT, B <: BlockContext](history: Vector[History[B]], result: RESULT)
  object WithHistory {
    def withNoHistory[RESULT, B <: BlockContext](handlingResult: RESULT): WithHistory[RESULT, B] =
      WithHistory(Vector.empty, handlingResult)
  }

  sealed trait RegularRequestResult[B <: BlockContext]
  object RegularRequestResult {
    final case class Allow[B <: BlockContext](blockContext: B, block: Block)
      extends RegularRequestResult[B]
    final case class ForbiddenBy[B <: BlockContext](blockContext: B, block: Block)
      extends RegularRequestResult[B]
    final case class ForbiddenByMismatched[B <: BlockContext](causes: NonEmptySet[ForbiddenCause])
      extends RegularRequestResult[B]
    final case class IndexNotFound[B <: BlockContext]()
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
    final case class Allow(userMetadata: UserMetadata, block: Block) extends UserMetadataRequestResult
    final case class Forbidden(causes: NonEmptySet[ForbiddenCause]) extends UserMetadataRequestResult
    case object PassedThrough extends UserMetadataRequestResult
  }

  sealed trait ForbiddenCause
  object ForbiddenCause {
    case object OperationNotAllowed extends ForbiddenCause
    case object ImpersonationNotSupported extends ForbiddenCause
    case object ImpersonationNotAllowed extends ForbiddenCause
  }

  trait AccessControlStaticContext {
    def usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine]
    def doesRequirePassword: Boolean
    def forbiddenRequestMessage: String
    def obfuscatedHeaders: Set[Header.Name]
  }
}
