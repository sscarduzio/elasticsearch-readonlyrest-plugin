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
import eu.timepit.refined.numeric.{NonNegative, Positive}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.ValueLevelCreationError
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator

import scala.compiletime.error
import scala.concurrent.duration.{Duration, FiniteDuration, TimeUnit}

object RefinedUtils {
  type PositiveFiniteDuration = FiniteDuration Refined Positive
  type NonNegativeFiniteDuration = FiniteDuration Refined NonNegative

  inline def nes(inline str: String): NonEmptyString = {
    inline if (str == "") error("NonEmptyString creation error, empty String") else NonEmptyString.unsafeFrom(str)
  }

  inline def positiveInt(inline i: Int): Refined[Int, Positive] = {
    inline if (i > 0) Refined.unsafeApply(i) else error("Int is not positive")
  }

  inline def nonNegativeInt(inline i: Int): Refined[Int, NonNegative] = {
    inline if (i >= 0) Refined.unsafeApply(i) else error("Int is not non-negative")
  }

  inline def positiveFiniteDuration(inline length: Long, inline timeUnit: TimeUnit): PositiveFiniteDuration = {
    inline if (length > 0) Duration(length, timeUnit).toRefinedPositiveUnsafe else error("FiniteDuration is not positive")
  }

  inline def nonNegativeFiniteDuration(inline length: Long, inline timeUnit: TimeUnit): NonNegativeFiniteDuration = {
    inline if (length >= 0) Duration(length, timeUnit).toRefinedNonNegativeUnsafe else error("FiniteDuration is not non-negative")
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

  extension (duration: Duration) {
    def toRefinedPositive: Either[String, PositiveFiniteDuration] = duration match {
      case v: FiniteDuration if v.toMillis > 0 =>
        Right(Refined.unsafeApply(v))
      case _ =>
        Left(s"Cannot map '${duration.toString}' to finite duration.")
    }

    def toRefinedPositiveUnsafe: PositiveFiniteDuration =
      duration.toRefinedPositive.fold(err => throw new IllegalArgumentException(err), identity)

    def toRefineNonNegative: Either[String, NonNegativeFiniteDuration] = duration match {
      case v: FiniteDuration if v.toMillis >= 0 =>
        Right(Refined.unsafeApply(v))
      case _ =>
        Left(s"Cannot map '${duration.toString}' to finite duration.")
    }

    def toRefinedNonNegativeUnsafe: NonNegativeFiniteDuration =
      duration.toRefineNonNegative.fold(err => throw new IllegalArgumentException(err), identity)
  }
}
