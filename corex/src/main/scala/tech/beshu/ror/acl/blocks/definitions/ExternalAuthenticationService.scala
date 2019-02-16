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
package tech.beshu.ror.acl.blocks.definitions

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

import cats.{Eq, Show}
import cats.implicits._
import com.softwaremill.sttp._
import cz.seznam.euphoria.shaded.guava.com.google.common.cache.{Cache, CacheBuilder}
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.Hashing
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.{BasicAuth, Header, Secret, User}
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

import scala.concurrent.duration.FiniteDuration

trait ExternalAuthenticationService extends Item {
  override type Id = Name
  def id: Id
  def authenticate(user: User.Id, secret: Secret): Task[Boolean]

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthenticationService {

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value)
  }
}

class BasicAuthHttpExternalAuthenticationService(override val id: ExternalAuthenticationService#Id,
                                                 uri: Uri,
                                                 successStatusCode: Int,
                                                 httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(user: User.Id, credentials: Secret): Task[Boolean] = {
    val basicAuthHeader = BasicAuth(user, credentials).header
    httpClient
      .send(sttp.get(uri).header(basicAuthHeader.name.value.value, basicAuthHeader.value.value))
      .map(_.code === successStatusCode)
  }
}

class JwtExternalAuthenticationService(override val id: ExternalAuthenticationService#Id,
                                       uri: Uri,
                                       successStatusCode: Int,
                                       httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(user: User.Id, secret: Secret): Task[Boolean] = {
    httpClient
      .send(sttp.get(uri).header(Header.Name.authorization.value.value, s"Bearer ${secret.value}"))
      .map(_.code === successStatusCode)
  }
}

class CachingExternalAuthenticationService(underlying: ExternalAuthenticationService, ttl: FiniteDuration Refined Positive)
  extends ExternalAuthenticationService {

  private val cache: Cache[String, String] =
    CacheBuilder
      .newBuilder
      .expireAfterWrite(ttl.value.toMillis, TimeUnit.MILLISECONDS)
      .build[String, String]

  override val id: ExternalAuthenticationService#Id = underlying.id

  override def authenticate(user: User.Id, secret: Secret): Task[Boolean] = {
    Option(cache.getIfPresent(user.value)) match {
      case Some(cachedUserHashedPass) => Task {
        cachedUserHashedPass === hashFrom(secret)
      }
      case None =>
        underlying
          .authenticate(user, secret)
          .map { authenticated =>
            if (authenticated) {
              cache.put(user.value, hashFrom(secret))
            }
            authenticated
          }
    }
  }

  private def hashFrom(password: Secret) = {
    Hashing.sha256.hashString(password.value, Charset.defaultCharset).toString
  }

}
