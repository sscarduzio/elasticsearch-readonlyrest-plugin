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
import scala.concurrent.duration.FiniteDuration

object RefinedUtils {
  inline def nes(inline str: String): NonEmptyString = {
    inline if (str == "") error(s"NonEmptyString creation error, empty String") else NonEmptyString.unsafeFrom(str) 
  }

  inline def positiveInt(inline i: Int): Refined[Int, Positive] = {
    inline if (i > 0) Refined.unsafeApply(i) else error(s"$i is not positive")
  }

  inline def positiveFiniteDuration(inline fd: FiniteDuration): Refined[FiniteDuration, Positive] = {
    Refined.unsafeApply(fd)
    //inline if (fd.length > 0) Refined.unsafeApply(fd) else error("FiniteDuration is not positive")
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
