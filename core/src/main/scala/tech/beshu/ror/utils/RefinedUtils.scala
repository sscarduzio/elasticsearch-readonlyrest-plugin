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

import cats.Show
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.ValueLevelCreationError
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator

import scala.compiletime.error
import scala.concurrent.duration.{FiniteDuration, TimeUnit}

object RefinedUtils {
  inline def nes(inline str: String): NonEmptyString = {
    inline if (str == "") error(s"NonEmptyString creation error, empty String") else NonEmptyString.unsafeFrom(str)
  }

  inline def positiveInt(inline i: Int): Refined[Int, Positive] = {
    inline if (i > 0) Refined.unsafeApply(i) else error(s"$i is not positive")
  }

  inline def positiveFiniteDuration(inline length: Long, inline timeUnit: TimeUnit): Refined[FiniteDuration, Positive] = {
    inline if (length > 0) Refined.unsafeApply(FiniteDuration.apply(length, timeUnit)) else error("FiniteDuration is not positive")
  }

  def positiveDecoder[T: Decoder : Show](valueToLong: T => Long): Decoder[T Refined Positive] =
    SyncDecoderCreator
      .from(Decoder[T])
      .emapE { value =>
        if (valueToLong(value) > 0) {
          Right(Refined.unsafeApply(value))
        } else {
          Left(ValueLevelCreationError(Message(s"Only positive values allowed. Found: ${Show[T].show(value)}")))
        }
      }
      .decoder
}
