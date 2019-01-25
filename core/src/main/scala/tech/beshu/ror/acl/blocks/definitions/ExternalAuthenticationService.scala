package tech.beshu.ror.acl.blocks.definitions

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

import cats.{Eq, Show}
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder}
import com.google.common.hash.Hashing
import com.softwaremill.sttp._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth

import scala.concurrent.duration.FiniteDuration

trait ExternalAuthenticationService extends Item {
  override type Id = Name
  def id: Id
  def authenticate(credentials: BasicAuth): Task[Boolean]

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthenticationService {

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value)
  }
}

class HttpExternalAuthenticationService(override val id: ExternalAuthenticationService#Id,
                                        uri: Uri,
                                        successStatusCode: Int,
                                        httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(credentials: BasicAuth): Task[Boolean] = {
    httpClient
      .send(sttp.get(uri).header(Header.Name.authorization.value, credentials.getBase64Value))
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

  override def authenticate(credentials: BasicAuth): Task[Boolean] = {
    Option(cache.getIfPresent(credentials.getUserName)) match {
      case Some(cachedUserHashedPass) => Task {
        cachedUserHashedPass === hashFrom(credentials.getPassword)
      }
      case None =>
        underlying
          .authenticate(credentials)
          .map { authenticated =>
            if (authenticated) {
              cache.put(credentials.getUserName, hashFrom(credentials.getPassword))
            }
            authenticated
          }
    }
  }

  private def hashFrom(password: String) = {
    Hashing.sha256.hashString(password, Charset.defaultCharset).toString
  }
}
