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
package tech.beshu.ror.accesscontrol.factory.decoders

import java.net.URI
import java.util.concurrent.TimeUnit

import cats.Show
import cats.data.NonEmptySet
import cats.implicits._
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import com.softwaremill.sttp.Uri
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.{Address, Group, Header, User}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.{DefinitionsLevelCreationError, ValueLevelCreationError}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object common extends Logging {

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
        import tech.beshu.ror.utils.StringWiseSplitter._
        val (errors, list) = str.split(";")
          .map(_.trim)
          .map { pairString =>
            pairString
              .toNonEmptyStringsTuple
              .left.map(_ => pairString)
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
    DecoderHelpers.decodeStringLikeNonEmpty.map(User.Id.apply)

  implicit val groupsUniqueNonEmptyListDecoder: Decoder[UniqueNonEmptyList[Group]] =
    SyncDecoderCreator
      .from(DecoderHelpers.decoderStringLikeOrUniqueNonEmptyList[Group])
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

  implicit val groupConvertible: Convertible[Group] = new Convertible[Group] {
    override def convert: String => Either[ConvertError, Group] = str => {
      NonEmptyString.from(str) match {
        case Right(nonEmptyResolvedValue) => Right(Group(nonEmptyResolvedValue))
        case Left(_) => Left(ConvertError("Group cannot be empty"))
      }
    }
  }

  implicit val addressConvertible: Convertible[Address] = new Convertible[Address] {
    override def convert: String => Either[ConvertError, Address] = str => {
      Address.from(str) match {
        case Some(address) => Right(address)
        case None => Left(ConvertError(s"Cannot create address (IP or hostname) from '$str'"))
      }
    }
  }

  implicit def valueLevelRuntimeSingleResolvableVariableDecoder[T : Convertible]: Decoder[RuntimeSingleResolvableVariable[T]] = {
    DecoderHelpers
      .singleVariableDecoder[T]
      .toSyncDecoder
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(ValueLevelCreationError(Message(error.show)))
      }
      .decoder
  }

  implicit def valueLevelRuntimeMultiResolvableVariableDecoder[T : Convertible]: Decoder[RuntimeMultiResolvableVariable[T]] = {
    DecoderHelpers
      .multiVariableDecoder[T]
      .toSyncDecoder
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(ValueLevelCreationError(Message(error.show)))
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

  implicit val httpClientConfigDecoder: Decoder[HttpClientsFactory.Config] =
    Decoder.instance { c =>
      for {
        connectionTimeout <- c.downFields("connection_timeout_in_sec", "connection_timeout").as[Option[FiniteDuration Refined Positive]]
        requestTimeout <- c.downFields("connection_request_timeout_in_sec", "connection_request_timeout", "request_timeout").as[Option[FiniteDuration Refined Positive]]
        connectionPoolSize <- c.downField("connection_pool_size").as[Option[Int Refined Positive]]
        validate <- c.downField("validate").as[Option[Boolean]]
      } yield HttpClientsFactory.Config(
        connectionTimeout.getOrElse(HttpClientsFactory.Config.default.connectionTimeout),
        requestTimeout.getOrElse(HttpClientsFactory.Config.default.requestTimeout),
        connectionPoolSize.getOrElse(HttpClientsFactory.Config.default.connectionPoolSize),
        validate.getOrElse(HttpClientsFactory.Config.default.validate)
      )
    }

  implicit val jsonPathDecoder: Decoder[JsonPath] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE[JsonPath] { jsonPathStr =>
      Try(JsonPath.compile(jsonPathStr))
        .toEither
        .left
        .map { ex =>
          logger.errorEx("JSON path compilation failed", ex)
          DefinitionsLevelCreationError(Message(s"Cannot compile '$jsonPathStr' to JSON path"))
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
