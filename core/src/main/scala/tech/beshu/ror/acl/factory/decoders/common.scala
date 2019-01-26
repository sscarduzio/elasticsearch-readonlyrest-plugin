package tech.beshu.ror.acl.factory.decoders

import java.net.URI
import java.util.concurrent.TimeUnit

import cats.data.NonEmptySet
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.ValueLevelCreationError
import tech.beshu.ror.acl.refined._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.ScalaOps._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

object common {

  implicit val decoderTupleList: Decoder[List[(NonEmptyString, NonEmptyString)]] =
    Decoder
      .decodeString
      .emapE { str =>
        val (errors, list) = str.split(",")
          .map(_.trim)
          .map { pairString =>
            pairString.split(":").toList match {
              case first :: second :: Nil =>
                val result = for {
                  nonEmptyA <- NonEmptyString.from(first)
                  nonEmptyB <- NonEmptyString.from(second)
                } yield (nonEmptyA, nonEmptyB)
                result.left.map(_ => pairString)
              case _ => Left(pairString)
            }
          }
          .toList
          .partitionEither
        if (errors.isEmpty) Right(list)
        else Left(ValueLevelCreationError(Message(s"Cannot parse pairs: ${errors.mkString(", ")}")))
      }

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

  implicit val groupDecoder: Decoder[Group] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(Group.apply)

  implicit val userIdDecoder: Decoder[User.Id] =
    DecoderHelpers.decodeStringLike.map(User.Id.apply)

  implicit val groupsNesDecoder: Decoder[NonEmptySet[Group]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[Group]
      .withError(ValueLevelCreationError(Message("Non empty list of groups are required")))

  implicit val usersNesDecoder: Decoder[NonEmptySet[User.Id]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[User.Id]
      .withError(ValueLevelCreationError(Message("Non empty list of user IDs are required")))

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
    Decoder
      .decodeLong
      .map(FiniteDuration(_, TimeUnit.SECONDS))
      .withErrorFromCursor { case (element, _) =>
        ValueLevelCreationError(Message(s"Cannot convert value '${element.noSpaces}' to duration"))
      }
}
