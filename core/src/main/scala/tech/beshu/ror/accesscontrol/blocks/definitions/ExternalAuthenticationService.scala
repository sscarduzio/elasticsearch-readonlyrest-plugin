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
package tech.beshu.ror.accesscontrol.blocks.definitions

import cats.implicits._
import cats.{Eq, Show}
import com.google.common.hash.Hashing
import com.softwaremill.sttp._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.CacheableExternalAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.accesscontrol.domain.{BasicAuth, Credentials, Header, User}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.utils.CacheableActionWithKeyMapping
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.nio.charset.Charset

trait ExternalAuthenticationService extends Item {
  override type Id = Name
  def id: Id
  def authenticate(credentials: Credentials)
                  (implicit requestId: RequestId): Task[Boolean]
  def serviceTimeout: PositiveFiniteDuration

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthenticationService {

  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

class BasicAuthHttpExternalAuthenticationService(override val id: ExternalAuthenticationService#Id,
                                                 uri: Uri,
                                                 successStatusCode: Int,
                                                 override val serviceTimeout: PositiveFiniteDuration,
                                                 httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(credentials: Credentials)
                           (implicit requestId: RequestId): Task[Boolean] = {
    val basicAuthHeader = BasicAuth(credentials).header
    httpClient
      .send(sttp.get(uri).header(basicAuthHeader.name.value.value, basicAuthHeader.value.value))
      .map(_.code === successStatusCode)
  }
}

class JwtExternalAuthenticationService(override val id: ExternalAuthenticationService#Id,
                                       uri: Uri,
                                       successStatusCode: Int,
                                       override val serviceTimeout: PositiveFiniteDuration,
                                       httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(credentials: Credentials)
                           (implicit requestId: RequestId): Task[Boolean] = {
    httpClient
      .send(sttp.get(uri).header(Header.Name.authorization.value.value, s"Bearer ${credentials.secret.value}"))
      .map(_.code === successStatusCode)
  }
}

class CacheableExternalAuthenticationServiceDecorator(underlying: ExternalAuthenticationService,
                                                      ttl: PositiveFiniteDuration)
  extends ExternalAuthenticationService {

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[Credentials, HashedUserCredentials, Boolean](
      ttl,
      (credentials, requestId) => authenticateAction(credentials)(requestId),
      hashCredential
    )

  override val id: ExternalAuthenticationService#Id = underlying.id

  override def authenticate(credentials: Credentials)
                           (implicit requestId: RequestId): Task[Boolean] = {
    cacheableAuthentication.call(credentials, serviceTimeout)
  }

  private def hashCredential(credentials: Credentials) = {
    HashedUserCredentials(credentials.user, Hashing.sha256.hashString(credentials.secret.value.value, Charset.defaultCharset).toString)
  }

  private def authenticateAction(credentials: Credentials)
                                (implicit requestId: RequestId) = {
    underlying.authenticate(credentials)
  }

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}

object CacheableExternalAuthenticationServiceDecorator {
  private[definitions] final case class HashedUserCredentials(user: User.Id, hashedCredentials: String)
}
