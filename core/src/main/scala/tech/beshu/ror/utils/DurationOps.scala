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
package tech.beshu.ror.utils

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import tech.beshu.ror.accesscontrol.refined._

import scala.concurrent.duration.{Duration, FiniteDuration}

object DurationOps {

  type PositiveFiniteDuration =  FiniteDuration Refined Positive

  implicit class RefinedDurationOps(val duration: Duration) extends AnyVal {
    def toRefinedPositive: Either[String, PositiveFiniteDuration] = duration match {
      case v: FiniteDuration if v.toMillis > 0 =>
        refineV[Positive](v)
      case _ =>
        Left(s"Cannot map '${duration.toString}' to finite duration.")
    }

    def toRefinedPositiveUnsafe: PositiveFiniteDuration =
      toRefinedPositive.fold(err => throw new IllegalArgumentException(err), identity)
  }

}
