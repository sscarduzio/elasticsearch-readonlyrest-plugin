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

import cats.Show
import cats.implicits._
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import com.softwaremill._
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.lemonlabs.uri.Uri
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.ResolvableJsonRepresentationOps._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.Json.ResolvableJsonRepresentation
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain.{Address, ClusterIndexName, GroupLike, GroupsLogic, Header, KibanaAccess, KibanaApp, KibanaIndexName, PermittedGroups, User}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, ValueLevelCreationError}
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.SyncDecoderCreator
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.net.URI
import java.util.concurrent.TimeUnit
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

  implicit val lemonLabsUriDecoder: Decoder[Uri] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { value =>
        Try(Uri.parse(value)) match {
          case Success(uri) => Right(uri)
          case Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to url")))
        }
      }
      .decoder

  implicit val sttpUriDecoder: Decoder[sttp.Uri] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .emapE { value =>
        Try(new URI(value)) match {
          case Success(javaUri) => Right(sttp.Uri(javaUri))
          case Failure(_) => Left(ValueLevelCreationError(Message(s"Cannot convert value '$value' to url")))
        }
      }
      .decoder

  implicit val groupLikeDecoder: Decoder[GroupLike] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(GroupLike.from)

  implicit val groupNameDecoder: Decoder[GroupName] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(GroupName.apply)

  implicit val userIdDecoder: Decoder[User.Id] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(User.Id.apply)

  implicit val idPatternDecoder: Decoder[UserIdPattern] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(UserIdPattern.apply)

  implicit val groupNamesUniqueNonEmptyListDecoder: Decoder[UniqueNonEmptyList[GroupName]] =
    SyncDecoderCreator
      .from(DecoderHelpers.decoderStringLikeOrUniqueNonEmptyList[GroupName])
      .withError(ValueLevelCreationError(Message("Non empty list of group names is required")))
      .decoder

  implicit val groupLikesUniqueNonEmptyListDecoder: Decoder[UniqueNonEmptyList[GroupLike]] =
    SyncDecoderCreator
      .from(DecoderHelpers.decoderStringLikeOrUniqueNonEmptyList[GroupLike])
      .withError(ValueLevelCreationError(Message("Non empty list of group names or/and patters is required")))
      .decoder

  implicit val permittedGroupsDecoder: Decoder[PermittedGroups] =
    groupLikesUniqueNonEmptyListDecoder.map(PermittedGroups.apply)

  implicit val usersUniqueNonEmptyListDecoder: Decoder[UniqueNonEmptyList[User.Id]] =
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[User.Id]
      .toSyncDecoder
      .withError(ValueLevelCreationError(Message("Non empty list of user IDs are required")))
      .decoder

  implicit val userPatternsUniqueNonEmptyListDecoder: Decoder[UniqueNonEmptyList[UserIdPattern]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[UserIdPattern]
      .toSyncDecoder
      .withError(ValueLevelCreationError(Message("Non empty list of user ID patterns are required")))
      .decoder
  }

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

  implicit val groupConvertible: Convertible[GroupLike] = new Convertible[GroupLike] {
    override def convert: String => Either[ConvertError, GroupLike] = str => {
      NonEmptyString.from(str) match {
        case Right(nonEmptyResolvedValue) => Right(GroupLike.from(nonEmptyResolvedValue))
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

  implicit val kibanaIndexNameConvertible: Convertible[KibanaIndexName] = new Convertible[KibanaIndexName] {
    override def convert: String => Either[Convertible.ConvertError, KibanaIndexName] = str => {
      ClusterIndexName.Local
        .fromString(str.replace(" ", "_"))
        .map(KibanaIndexName.apply)
        .toRight(Convertible.ConvertError("Kibana index name cannot be empty"))
    }
  }

  implicit def valueLevelRuntimeSingleResolvableVariableDecoder[T : Convertible](implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeSingleResolvableVariable[T]] = {
    DecoderHelpers
      .singleVariableDecoder[T](variableCreator)
      .toSyncDecoder
      .emapE {
        case Right(value) => Right(value)
        case Left(error) => Left(ValueLevelCreationError(Message(error.show)))
      }
      .decoder
  }

  implicit def valueLevelRuntimeMultiResolvableVariableDecoder[T : Convertible](implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[RuntimeMultiResolvableVariable[T]] = {
    DecoderHelpers
      .multiVariableDecoder[T](variableCreator)
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

  implicit val kibanaAccessDecoder: Decoder[KibanaAccess] =
    DecoderHelpers
      .decodeStringLike
      .map(_.toLowerCase)
      .toSyncDecoder
      .emapE[KibanaAccess] {
        case "ro" => Right(KibanaAccess.RO)
        case "ro_strict" => Right(KibanaAccess.ROStrict)
        case "rw" => Right(KibanaAccess.RW)
        case "api_only" =>  Right(KibanaAccess.ApiOnly)
        case "admin" => Right(KibanaAccess.Admin)
        case "unrestricted" => Right(KibanaAccess.Unrestricted)
        case unknown => Left(CoreCreationError.ValueLevelCreationError(Message(
          s"Unknown kibana access '$unknown'. Available options: 'ro', 'ro_strict', 'rw', 'api_only', 'admin', 'unrestricted'"
        )))
      }
      .decoder

  implicit val kibanaAppDecoder: Decoder[KibanaApp] = nonEmptyStringDecoder.emap(KibanaApp.from)

  implicit val groupsLogicAndDecoder: Decoder[GroupsLogic.And] =
    permittedGroupsDecoder.map(GroupsLogic.And.apply)

  implicit val groupsLogicOrDecoder: Decoder[GroupsLogic.Or] =
    permittedGroupsDecoder.map(GroupsLogic.Or.apply)

  implicit def resolvableJsonRepresentationDecoder(implicit variableCreator: RuntimeResolvableVariableCreator): Decoder[ResolvableJsonRepresentation] =
    Decoder
      .decodeJson
      .toSyncDecoder
      .emapE { json =>
        json
          .toJsonRepresentation
          .toResolvable
          .left.map { error => ValueLevelCreationError(Message(error.show)) }
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
