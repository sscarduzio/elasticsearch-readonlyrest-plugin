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
package tech.beshu.ror.configuration

import java.time.{Clock, Instant}

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import scala.concurrent.duration.FiniteDuration

sealed trait TestRorConfig
object TestRorConfig {
  case object NotSet extends TestRorConfig

  final case class Present(rawConfig: RawRorConfig,
                           expiration: Present.ExpirationConfig) extends TestRorConfig {
    def isExpired(clock: Clock): Boolean = {
      expiration.validTo.isBefore(clock.instant())
    }
  }

  object Present {
    final case class ExpirationConfig(ttl: FiniteDuration Refined Positive,
                                      validTo: Instant)
  }
}
