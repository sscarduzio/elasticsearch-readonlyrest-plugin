package tech.beshu.ror.acl.factory.decoders

import java.net.URI
import java.util.concurrent.TimeUnit

import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.circe.Decoder
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.ValueLevelCreationError
import tech.beshu.ror.acl.refined._
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

object common {

  implicit val positiveFiniteDurationDecoder: Decoder[FiniteDuration Refined Positive] =
    finiteDurationStringDecoder
      .or(finiteDurationInSecondsDecoder)
      .emapE { value =>
        refineV[Positive](value)
          .left
          .map(_ => ValueLevelCreationError(Message(s"Only positive durations allowed. Found: ${value.toString()}")))
      }

  implicit val uriDecoder: Decoder[Uri] =
    Decoder
      .decodeString
      .emapE { value =>
        Try(new URI(value)) match {
          case Success(javaUri) => Right(Uri(javaUri))
          case Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to url")))
        }
      }

  private lazy val finiteDurationStringDecoder: Decoder[FiniteDuration] =
    DecoderHelpers
      .decodeStringLike
      .emapE { value =>
        Try(Duration(value)) match {
          case Success(v: FiniteDuration) => Right(v)
          case Success(_) | Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to duration")))
        }
      }

  private lazy val finiteDurationInSecondsDecoder: Decoder[FiniteDuration] =
    Decoder.decodeLong.map(FiniteDuration(_, TimeUnit.SECONDS))
}
