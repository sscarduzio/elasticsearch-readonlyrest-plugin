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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.PkiAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.uniquelist.UniqueList

/** Reads groups out of the TLS client certificate the connection was established with.
  *
  * The groups are external ones, so they reach the ACL through the same mapping machinery LDAP and the
  * external group providers use: values a `users` entry doesn't map are simply discarded, which is what
  * makes a filtering mechanism unnecessary.
  */
final class PkiAuthorizationRule(
    val settings: Settings,
    override implicit val userIdCaseSensitivity: CaseSensitivity,
    override val impersonation: Impersonation
) extends BaseAuthorizationRule
    with RequestIdAwareLogging {

  override val name: Rule.Name = PkiAuthorizationRule.Name.name

  override val groupsLogic: GroupsLogic = settings.groupsLogic

  override protected def userGroups[B <: BlockContext](blockContext: B, user: LoggedUser)(
      implicit requestId: RequestId
  ): Task[UniqueList[Group]] = Task.delay {
    blockContext.requestContext.restRequest.clientCertificate
      .filter(settings.pki.scope.accepts)
      .map(groupsFrom)
      .getOrElse(UniqueList.empty)
  }

  override protected def mockedGroupsOf(user: User.Id, mocksProvider: MocksProvider)(
      implicit requestId: RequestId
  ): Groups = {
    // groups live on the caller's certificate, and an impersonated request carries none of its own
    Groups.CannotCheck
  }

  private def groupsFrom(certificate: ClientCertificate)(
      implicit requestId: RequestId
  ) = {
    val groups = settings.pki.groupsExtraction.toList
      .flatMap(_.extractFrom(certificate))
      .flatMap(groupIdFrom)
    if (groups.isEmpty) {
      logger.debug(
        s"The '${settings.pki.id.value.show}' PKI could not extract any group from the certificate of '${certificate.subjectDn.value.show}'."
      )
    }
    UniqueList.from(groups)
  }

  private def groupIdFrom(value: String) =
    NonEmptyString.from(value).toOption.map(GroupIdLike.GroupId.apply).map(Group.from)

}

object PkiAuthorizationRule {

  implicit case object Name extends RuleName[PkiAuthorizationRule] {
    override val name = Rule.Name("pki_authorization")
  }

  final case class Settings(pki: PkiDef, groupsLogic: GroupsLogic)
}
