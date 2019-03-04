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
package tech.beshu.ror.acl.factory.decoders

import java.net.URI
import java.util.concurrent.TimeUnit

import cats.Show
import cats.implicits._
import cats.data.NonEmptySet
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.acl.domain.{Address, Group, Header, User}
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Value.ConvertError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.ValueLevelCreationError
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.refined._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.ScalaOps._
import tech.beshu.ror.acl.utils.SyncDecoderCreator

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

object common {

  implicit val nonEmptyStringDecoder: Decoder[NonEmptyString] =
    Decoder
      .decodeString
      .toSyncDecoder
      .emap { str =>
        NonEmptyString.unapply(str) match {
          case Some(res) => Right(res)
          case None => Left("Cannot decode empty string")
        }
      }
      .decoder

  implicit val decoderTupleListDecoder: Decoder[List[(NonEmptyString, NonEmptyString)]] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
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
      .decoder

  implicit val positiveFiniteDurationDecoder: Decoder[FiniteDuration Refined Positive] = {
    implicit val finiteDurationDecoder: Decoder[FiniteDuration] = finiteDurationStringDecoder.or(finiteDurationInSecondsDecoder)
    positiveValueDecoder[FiniteDuration]
  }

  implicit def positiveValueDecoder[V: Decoder: Show](implicit v: Validate[V, Positive]): Decoder[V Refined Positive] =
    SyncDecoderCreator
      .from(Decoder[V])
      .emapE { value =>
        refineV[Positive](value)
          .left
          .map(_ => ValueLevelCreationError(Message(s"Only positive values allowed. Found: ${value.show}")))
      }
      .decoder

  implicit val uriDecoder: Decoder[Uri] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { value =>
        Try(new URI(value)) match {
          case Success(javaUri) => Right(Uri(javaUri))
          case Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to url")))
        }
      }
      .decoder

  implicit val groupDecoder: Decoder[Group] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(Group.apply)

  implicit val userIdDecoder: Decoder[User.Id] =
    DecoderHelpers.decodeStringLike.map(User.Id.apply)

  implicit val groupsNesDecoder: Decoder[NonEmptySet[Group]] =
    SyncDecoderCreator
      .from(DecoderHelpers.decodeStringLikeOrNonEmptySet[Group])
      .withError(ValueLevelCreationError(Message("Non empty list of groups are required")))
      .decoder

  implicit val usersNesDecoder: Decoder[NonEmptySet[User.Id]] =
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[User.Id]
      .toSyncDecoder
      .withError(ValueLevelCreationError(Message("Non empty list of user IDs are required")))
      .decoder

  implicit val headerName: Decoder[Header.Name] =
    Decoder
      .decodeString
      .toSyncDecoder
      .emapE { str =>
        NonEmptyString.unapply(str) match {
          case Some(value) => Right(Header.Name(value))
          case None => Left(ValueLevelCreationError(Message(s"Header name cannot be empty string")))
        }
      }
      .decoder

  implicit val groupValueDecoder: Decoder[Value[Group]] =
    DecoderHelpers
      .valueDecoder[Group] { rv =>
      NonEmptyString.from(rv.value) match {
        case Right(nonEmptyResolvedValue) => Right(Group(nonEmptyResolvedValue))
        case Left(_) => Left(ConvertError(rv, "Group cannot be empty"))
      }
    }
      .toSyncDecoder
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(ValueLevelCreationError(Message(s"${error.msg}: ${error.resolvedValue.show}")))
      }
      .decoder

  implicit val addressValueDecoder: Decoder[Value[Address]] = {
    DecoderHelpers
      .valueDecoder[Address] { rv =>
      Address.from(rv.value) match {
        case Some(address) => Right(address)
        case None => Left(ConvertError(rv, s"Cannot create address (IP or hostname) from ${rv.value}"))
      }
    }
      .toSyncDecoder
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(ValueLevelCreationError(Message(s"${error.msg}: ${error.resolvedValue.show}")))
      }
      .decoder
  }

  implicit val ipAddressDecoder: Decoder[IpAddress] =
    Decoder
      .decodeString
      .toSyncDecoder
      .emapE { str =>
        IpAddress(str) match {
          case Some(ip) => Right(ip)
          case None => Left(ValueLevelCreationError(Message(s"Cannot create IP address from $str")))
        }
      }
      .decoder

  implicit val portDecoder: Decoder[Port] =
    Decoder
      .decodeInt
      .toSyncDecoder
      .emapE { int =>
        Port(int) match {
          case Some(ip) => Right(ip)
          case None => Left(ValueLevelCreationError(Message(s"Cannot create port from $int")))
        }
      }
      .decoder

  implicit val socketAddressDecoder: Decoder[SocketAddress[IpAddress]] =
    Decoder
      .decodeString
      .toSyncDecoder
      .emapE { str =>
        SocketAddress.fromString(str) match {
          case Some(socketAddress) => Right(socketAddress)
          case None => Left(ValueLevelCreationError(Message(s"Cannot create socket address from $str")))
        }
      }
      .decoder

  private lazy val finiteDurationStringDecoder: Decoder[FiniteDuration] =
    DecoderHelpers
      .decodeStringLike
      .toSyncDecoder
      .emapE { value =>
        Try(Duration(value)) match {
          case Success(v: FiniteDuration) => Right(v)
          case Success(_) | Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to duration")))
        }
      }
      .decoder

  private lazy val finiteDurationInSecondsDecoder: Decoder[FiniteDuration] =
    Decoder
      .decodeLong
      .toSyncDecoder
      .map(FiniteDuration(_, TimeUnit.SECONDS))
      .withErrorFromCursor { case (element, _) =>
        ValueLevelCreationError(Message(s"Cannot convert value '${element.noSpaces}' to duration"))
      }
      .decoder

}
