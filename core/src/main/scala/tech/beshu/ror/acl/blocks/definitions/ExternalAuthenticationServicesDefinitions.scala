package tech.beshu.ror.acl.blocks.definitions

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

import cats.implicits._
import cats.Eq
import com.google.common.cache.{Cache, CacheBuilder}
import com.google.common.hash.Hashing
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService.Name
import tech.beshu.ror.utils.BasicAuthUtils.BasicAuth
import com.softwaremill.sttp._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import tech.beshu.ror.acl.blocks.definitions.HttpExternalAuthenticationService.HttpClient

import scala.concurrent.duration.FiniteDuration

final case class ExternalAuthenticationServicesDefinitions(services: Set[ExternalAuthenticationService])

trait ExternalAuthenticationService {
  def name: Name

  def authenticate(credentials: BasicAuth): Task[Boolean]
}

// todo: close http client
class HttpExternalAuthenticationService(override val name: Name,
                                        uri: Uri,
                                        successStatusCode: Int,
                                        httpClient: HttpClient)
  extends ExternalAuthenticationService {

  override def authenticate(credentials: BasicAuth): Task[Boolean] = ???
}

class CachingExternalAuthenticationService(underlying: ExternalAuthenticationService, ttl: FiniteDuration Refined Positive)
  extends ExternalAuthenticationService {

  private val cache: Cache[String, String] =
    CacheBuilder
      .newBuilder
      .expireAfterWrite(ttl.value.toMillis, TimeUnit.MILLISECONDS)
      .build

  override val name: Name = underlying.name

  override def authenticate(credentials: BasicAuth): Task[Boolean] = {
    Option(cache.getIfPresent(credentials.getUserName)) match {
      case Some(cachedUserHashedPass) => Task {
        cachedUserHashedPass === hashFrom(credentials.getPassword)
      }
      case None =>
        underlying
          .authenticate(credentials)
          .map { authenticated =>
            if(authenticated) {
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

object HttpExternalAuthenticationService {
  type HttpClient = SttpBackend[Task, Nothing]
}

object ExternalAuthenticationService {

  final case class Name(value: String) extends AnyVal

  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  }

}
