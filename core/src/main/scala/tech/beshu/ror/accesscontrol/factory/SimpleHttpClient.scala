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
package tech.beshu.ror.accesscontrol.factory

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.TimeUnit
import scala.language.postfixOps

trait SimpleHttpClient[F[_]] {
  def send(request: HttpClient.Request)
          (implicit requestId: RequestId): F[HttpClient.Response]

  def close(): F[Unit]
}

object SimpleHttpClient {
  final case class Config(connectionTimeout: PositiveFiniteDuration,
                          requestTimeout: PositiveFiniteDuration,
                          connectionPoolSize: Int Refined Positive,
                          validate: Boolean)

  object Config {
    val default: Config = Config(
      connectionTimeout = positiveFiniteDuration(2, TimeUnit.SECONDS),
      requestTimeout = positiveFiniteDuration(5, TimeUnit.SECONDS),
      connectionPoolSize = positiveInt(30),
      validate = true
    )
  }
}

trait SimpleHttpClientCreator[F[_], +C <: SimpleHttpClient[F]] {
  def create(config: Config): C
}
