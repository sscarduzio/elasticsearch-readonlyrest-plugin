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
package tech.beshu.ror.accesscontrol.response

import cats.Show
import cats.data.NonEmptyList
import cats.implicits.*
import tech.beshu.ror.accesscontrol.AccessControlList.{AccessControlStaticContext, ForbiddenCause}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain.Header.findHeader
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RestRequest
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.*

final class ForbiddenResponseContext(
    aclStaticContext: Option[AccessControlStaticContext],
    forbiddenCauses: NonEmptyList[ForbiddenResponseContext.Cause],
    responseForRorKbnPlugin: Boolean
) {

  import ForbiddenResponseContext.forbiddenCauseShow

  def responseMessage: String = {
    forbiddenRequestMessageFromBlock
      .orElse(customForbiddenRequestMessage)
      .getOrElse(GlobalSettings.defaultForbiddenRequestMessage)
  }

  def causes: NonEmptyList[String] = {
    forbiddenCauses.map(_.show)
  }

  /**
   * The ROR Kibana plugin marks each request it sends with the license type header. It runs its own
   * login, which the basic auth prompt of the browser breaks, so ROR asks such a request for no
   * credentials. Every other client keeps the behaviour of the `prompt_for_basic_auth` setting.
   */
  def shouldAddBasicAuthPrompt: Boolean = {
    !responseForRorKbnPlugin && aclStaticContext.exists(_.doesRequirePassword)
  }

  private def customForbiddenRequestMessage: Option[String] = aclStaticContext.map(_.forbiddenRequestMessage)

  private def forbiddenRequestMessageFromBlock: Option[String] =
    block.map(_.policy).flatMap {
      case Policy.Allow                      => None
      case Policy.Forbid(maybeCustomMessage) => maybeCustomMessage
    }

  private def block: Option[Block] = {
    forbiddenCauses.toList.flatMap(blockFrom).headOption
  }

  private def blockFrom: Cause => Option[Block] = {
    case ForbiddenBlockMatch(block) => Some(block)
    case OperationNotAllowed        => None
    case ImpersonationNotSupported  => None
    case ImpersonationNotAllowed    => None
    case RorNotReadyYet             => None
    case RorNotEnabled              => None
    case RorFailedToStart           => None
    case TestSettingsNotConfigured  => None
  }

}

object ForbiddenResponseContext {
  sealed trait Cause

  object Cause {

    def fromMismatchedCause(cause: ForbiddenCause): Cause = {
      cause match {
        case ForbiddenCause.OperationNotAllowed       => OperationNotAllowed
        case ForbiddenCause.ImpersonationNotSupported => ImpersonationNotSupported
        case ForbiddenCause.ImpersonationNotAllowed   => ImpersonationNotAllowed
      }
    }

  }

  final case class ForbiddenBlockMatch(block: Block) extends Cause
  case object OperationNotAllowed extends Cause
  case object ImpersonationNotSupported extends Cause
  case object ImpersonationNotAllowed extends Cause
  case object RorNotReadyYet extends Cause
  case object RorNotEnabled extends Cause
  case object RorFailedToStart extends Cause
  case object TestSettingsNotConfigured extends Cause

  def from(
      causes: NonEmptyList[ForbiddenResponseContext.Cause],
      aclStaticContext: AccessControlStaticContext,
      restRequest: RestRequest
  ): ForbiddenResponseContext =
    new ForbiddenResponseContext(
      aclStaticContext = Some(aclStaticContext),
      forbiddenCauses = causes,
      responseForRorKbnPlugin = sentByRorKbnPlugin(restRequest)
    )

  private def sentByRorKbnPlugin(restRequest: RestRequest): Boolean =
    findHeader(Header.Name.rorKbnLicenseType, in = restRequest.allHeaders).isDefined

  private implicit val forbiddenCauseShow: Show[Cause] = Show.show {
    case ForbiddenBlockMatch(_)    => "FORBIDDEN_BY_BLOCK"
    case OperationNotAllowed       => "OPERATION_NOT_ALLOWED"
    case ImpersonationNotSupported => "IMPERSONATION_NOT_SUPPORTED"
    case ImpersonationNotAllowed   => "IMPERSONATION_NOT_ALLOWED"
    case RorNotReadyYet            => "READONLYREST_NOT_READY_YET"
    case RorNotEnabled             => "READONLYREST_NOT_ENABLED"
    case RorFailedToStart          => "READONLYREST_FAILED_TO_START"
    case TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
  }

  trait ResponseCreator[RESPONSE] {
    final def createRorStartingFailureResponse(): RESPONSE =
      create(new ForbiddenResponseContext(None, NonEmptyList.one(RorFailedToStart)))

    final def createRorNotReadyYetResponse(): RESPONSE =
      create(new ForbiddenResponseContext(None, NonEmptyList.one(RorNotReadyYet)))

    final def createRorNotEnabledResponse(): RESPONSE =
      create(new ForbiddenResponseContext(None, NonEmptyList.one(RorNotEnabled)))

    final def createTestSettingsNotConfiguredResponse(): RESPONSE =
      create(new ForbiddenResponseContext(None, NonEmptyList.one(TestSettingsNotConfigured)))

    final def createOperationNotAllowedResponse(): RESPONSE =
      create(new ForbiddenResponseContext(None, NonEmptyList.one(OperationNotAllowed)))

    def create(context: ForbiddenResponseContext): RESPONSE
  }

}
