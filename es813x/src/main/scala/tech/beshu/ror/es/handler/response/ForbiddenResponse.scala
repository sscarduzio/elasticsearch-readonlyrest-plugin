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
package tech.beshu.ror.es.handler.response

import cats.Show
import cats.implicits._
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.accesscontrol.AccessControl.{AccessControlStaticContext, ForbiddenCause}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

import scala.jdk.CollectionConverters._

class ForbiddenResponse private(aclStaticContext: Option[AccessControlStaticContext],
                                causes: List[ForbiddenResponse.Cause])
  extends ElasticsearchException(
    aclStaticContext.map(_.forbiddenRequestMessage).getOrElse(GlobalSettings.defaultForbiddenRequestMessage)
  ) {

  import ForbiddenResponse.forbiddenCauseShow

  addMetadata("es.due_to", causes.map(_.show).asJava)

  aclStaticContext match {
    case Some(context) if context.doesRequirePassword =>
      addHeader("WWW-Authenticate", "Basic")
    case _ =>
  }

  override def status(): RestStatus = aclStaticContext match {
    case Some(context) if context.doesRequirePassword =>
      RestStatus.UNAUTHORIZED
    case _ =>
      RestStatus.FORBIDDEN
  }
}

object ForbiddenResponse {

  sealed trait Cause
  object Cause {
    def fromMismatchedCause(cause: ForbiddenCause): Cause = {
      cause match {
        case ForbiddenCause.OperationNotAllowed => OperationNotAllowed
        case ForbiddenCause.ImpersonationNotSupported => ImpersonationNotSupported
        case ForbiddenCause.ImpersonationNotAllowed => ImpersonationNotAllowed
      }
    }
  }
  case object ForbiddenBlockMatch extends Cause
  case object OperationNotAllowed extends Cause
  case object ImpersonationNotSupported extends Cause
  case object ImpersonationNotAllowed extends Cause
  case object RorNotReadyYet extends Cause
  case object RorNotEnabled extends Cause
  case object RorFailedToStart extends Cause
  case object TestSettingsNotConfigured extends Cause

  def create(causes: List[ForbiddenResponse.Cause],
             aclStaticContext: AccessControlStaticContext): ForbiddenResponse =
    new ForbiddenResponse(Some(aclStaticContext), causes)

  def createRorStartingFailureResponse(): ForbiddenResponse =
    new ForbiddenResponse(None, RorFailedToStart :: Nil)

  def createRorNotReadyYetResponse(): ForbiddenResponse =
    new ForbiddenResponse(None, RorNotReadyYet :: Nil)

  def createRorNotEnabledResponse(): ForbiddenResponse =
    new ForbiddenResponse(None, RorNotEnabled :: Nil)

  def createTestSettingsNotConfiguredResponse(): ForbiddenResponse =
    new ForbiddenResponse(None, TestSettingsNotConfigured :: Nil)

  private implicit val forbiddenCauseShow: Show[Cause] = Show.show {
    case ForbiddenBlockMatch => "FORBIDDEN_BY_BLOCK"
    case OperationNotAllowed => "OPERATION_NOT_ALLOWED"
    case ImpersonationNotSupported => "IMPERSONATION_NOT_SUPPORTED"
    case ImpersonationNotAllowed => "IMPERSONATION_NOT_ALLOWED"
    case RorNotReadyYet => "READONLYREST_NOT_READY_YET"
    case RorNotEnabled => "READONLYREST_NOT_ENABLED"
    case RorFailedToStart => "READONLYREST_FAILED_TO_START"
    case TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
  }
}
