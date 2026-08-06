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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.PkiAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.AvailableLocalUsers.Known
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{
  AvailableLocalUsers,
  CaseSensitivity,
  ClientCertificate,
  LocalUsers,
  RequestId,
  User
}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

/** Authenticates a caller by the TLS client certificate their connection was established with.
  *
  * The certificate has already been verified by whichever component terminated TLS, so a failure here only
  * ever means "this valid certificate does not identify a user I recognise", never "this certificate might
  * be forged". A request arriving without a certificate does not match, rather than being rejected, which is
  * what lets certificate-bearing services and password-bearing humans share one port.
  */
final class PkiAuthenticationRule(
    val settings: Settings,
    override implicit val userIdCaseSensitivity: CaseSensitivity,
    override val impersonation: Impersonation
) extends BaseAuthenticationRule
    with RequestIdAwareLogging {

  private val userMatcher = settings.userIds.map(PatternsMatcher.create(_))

  override val name: Rule.Name = PkiAuthenticationRule.Name.name

  override val localUsers: LocalUsers = settings.userIds match {
    case Some(userIds) => LocalUsers.Available(Known(userIds))
    // any certificate the configured PKI speaks for identifies a user, and that population is unbounded
    case None => LocalUsers.Available(AvailableLocalUsers.Unknown)
  }

  override protected def tryToAuthenticateUser[B <: BlockContext: BlockContextUpdater](
      blockContext: B
  ): Task[Decision[B]] = Task.delay {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    val result = for {
      certificate <- acceptedCertificateOf(blockContext.requestContext)
      userId <- usernameFrom(certificate)
      _ <- checkUserAllowed(userId)
    } yield {
      blockContext.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(userId)))
    }
    result.toDecision
  }

  override protected[rules] def exists(user: User.Id, mocksProvider: MocksProvider)(
      implicit requestId: RequestId
  ): Task[UserExistence] = Task.delay {
    userMatcher match {
      case Some(matcher) if matcher.`match`(user) => UserExistence.Exists
      case Some(_)                                => UserExistence.NotExist
      // without a list of accepted users there is nothing to check the impersonated user against
      case None => UserExistence.CannotCheck
    }
  }

  private def acceptedCertificateOf(requestContext: RequestContext) = {
    requestContext.restRequest.clientCertificate
      .toRight(AuthenticationFailed("No client certificate was presented"))
      .filterOrElse(
        settings.pki.scope.accepts,
        AuthenticationFailed(s"Client certificate is out of the scope of the '${settings.pki.id.value.show}' PKI")
      )
  }

  private def usernameFrom(certificate: ClientCertificate)(
      implicit requestId: RequestId
  ) = {
    settings.pki.usersExtraction.extractFrom(certificate) match {
      case Nil =>
        logger.warn(
          s"The '${settings.pki.id.value.show}' PKI could not extract a username from the certificate of '${certificate.subjectDn.value.show}'. Check the 'users' section of the PKI definition."
        )
        Left(AuthenticationFailed("Cannot extract a username from the client certificate"))
      case username :: rest =>
        if (rest.nonEmpty) {
          logger.debug(
            s"The certificate of '${certificate.subjectDn.value.show}' carries several usernames (${rest.size.show} more); using '${username.show}'."
          )
        }
        NonEmptyString
          .from(username)
          .map(value => User.Id(value))
          .left
          .map(_ => AuthenticationFailed("The username extracted from the client certificate is empty"))
    }
  }

  private def checkUserAllowed(userId: User.Id) = {
    userMatcher match {
      case Some(matcher) if !matcher.`match`(userId) =>
        Left(AuthenticationFailed("User not found in allowed users list"))
      case Some(_) | None => Right(())
    }
  }

}

object PkiAuthenticationRule {

  implicit case object Name extends RuleName[PkiAuthenticationRule] {
    override val name = Rule.Name("pki_authentication")
  }

  final case class Settings(pki: PkiDef, userIds: Option[UniqueNonEmptyList[User.Id]])
}
