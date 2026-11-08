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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import cats.effect.Resource
import cats.implicits.*
import com.unboundid.ldap.sdk.{DN, LDAPConnection, LDAPException, SearchResultEntry, ServerSet}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.{Fiber, Task}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.Dn
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{
  GroupAttribute,
  GroupsFromUserEntry
}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, GroupName}
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

private[ror] object UnboundidOps extends RequestIdAwareLogging {

  extension (serverSet: ServerSet) {

    // Each `use` connects to the server set, and the connection is closed after the use. The use fails with a
    // `TimeoutException` when no connection comes within `timeout`. A connection that comes later is closed then.
    // A cancel waits until the connection comes or the timeout expires.
    def connectionWithin(timeout: FiniteDuration): Resource[Task, Either[LDAPException, LDAPConnection]] =
      Resource.make(connectWithin(timeout))(closeIfConnected)

    private def connectWithin(timeout: FiniteDuration): Task[Either[LDAPException, LDAPConnection]] =
      for {
        connecting <- connect().start
        connection <- connecting.join.timeoutTo(timeout, closeWhenItComes(connecting) >> noConnectionWithin(timeout))
      } yield connection

    private def connect(): Task[Either[LDAPException, LDAPConnection]] =
      onBlockingScheduler(Task(Either.catchOnly[LDAPException](serverSet.getConnection)))

    private def noConnectionWithin(timeout: FiniteDuration): Task[Nothing] =
      Task.raiseError(new TimeoutException(s"No LDAP connection within $timeout"))

    // A timeout cannot stop the SDK call, so the close does not wait for the connection.
    private def closeWhenItComes(connecting: Fiber[Either[LDAPException, LDAPConnection]]): Task[Unit] =
      connecting.join
        .flatMap(closeIfConnected)
        .onErrorHandleWith(ex => Task(noRequestIdLogger.warnEx("Problem during getting LDAP connection", ex)))
        .startAndForget

    private def closeIfConnected(connection: Either[LDAPException, LDAPConnection]): Task[Unit] = connection match {
      case Right(connection) =>
        onBlockingScheduler(Task.delay(connection.close()))
          .onErrorHandleWith(ex => Task(noRequestIdLogger.warnEx("Problem during closing LDAP connection", ex)))
      case Left(_) => Task.unit
    }

    private def onBlockingScheduler[A](call: Task[A]): Task[A] =
      doPrivileged(call)(RorSchedulers.blockingScheduler)

  }

  extension (entry: SearchResultEntry) {

    private[implementations] def toLdapGroup(groupAttribute: GroupAttribute): Option[LdapGroup] = {
      for {
        groupId <- Option(entry.getAttributeValue(groupAttribute.id.value.value))
          .flatMap(NonEmptyString.unapply)
          .map(GroupId.apply)
        groupName <- Option(entry.getAttributeValue(groupAttribute.name.value.value))
          .flatMap(NonEmptyString.unapply)
          .map(GroupName.apply)
        dn <- Option(entry.getDN).flatMap(NonEmptyString.unapply).map(Dn.apply)
      } yield LdapGroup(Group(groupId, groupName), dn)
    }

    private[implementations] def toLdapGroups(mode: GroupsFromUserEntry): List[LdapGroup] = {
      Option(entry.getAttributeValues(mode.groupsFromUserAttribute.value.value)).toList
        .flatMap(_.toList)
        .flatMap(NonEmptyString.unapply)
        .flatMap(ldapGroupFromDn(_, mode))
    }

    private def ldapGroupFromDn(dnString: NonEmptyString, mode: GroupsFromUserEntry) = {
      val dn = new DN(dnString.value)
      if (dn.isDescendantOf(mode.searchGroupBaseDN.value.value, false)) {
        Try {
          dn.getRDN.getAttributes.toList
            .filter(_.getBaseName === mode.groupIdAttribute.value.value)
            .map(_.getValue)
            .headOption
        }.toOption.flatten
          .flatMap(NonEmptyString.unapply)
          .map { groupId => LdapGroup(Group.from(GroupId(groupId)), Dn(dnString)) }
      } else {
        None
      }
    }

  }

}
