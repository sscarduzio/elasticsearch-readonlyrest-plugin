package tech.beshu.ror.acl.factory.decoders.definitions

import cats.implicits._
import cats.data.NonEmptySet
import com.comcast.ip4s.{IpAddress, Port, SocketAddress}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.circe.{Decoder, DecodingFailure}
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations._
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.ConnectionMethod.{SeveralServers, SingleServer}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, SslSettings}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.acl.blocks.definitions.ldap.{Dn, LdapAuthenticationService, LdapAuthorizationService, LdapService}
import tech.beshu.ror.acl.domain.Secret
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.{DefinitionsLevelCreationError, ValueLevelCreationError}
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.refined._
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.acl.utils._

import scala.collection.SortedSet
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

object LdapServicesDecoder {

  implicit val ldapServiceNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  val ldapServicesDefinitionsDecoder: AsyncDecoder[Definitions[LdapService]] = {
    AsyncDecoderCreator.instance { c =>
      DefinitionsBaseDecoder.instance[Task, LdapService]("ldaps").apply(c)
    }
  }

  private implicit lazy val ldapServiceDecoder: AsyncDecoder[LdapService] =
    AsyncDecoderCreator.instance { c =>
      authorizationServiceDecoder(c).flatMap {
        case Left(_) => authenticationServiceDecoder(c)
        case r@Right(_) => Task.now(r)
      }
    }

  private lazy val authenticationServiceDecoder: AsyncDecoder[LdapAuthenticationService] =
    AsyncDecoderCreator
      .instance[LdapAuthenticationService] { c =>
      val ldapServiceDecodingResult = for {
        name <- c.downField("name").as[LdapService.Name]
        connectionConfig <- connectionConfigDecoder(c)
        userSearchFiler <- userSearchFilerConfigDecoder(c)
      } yield UnboundidLdapAuthenticationService.create(
        name,
        connectionConfig,
        userSearchFiler
      )
      ldapServiceDecodingResult match {
        case Left(error) => Task.now(Left(error))
        case Right(task) => task.flatMap {
          case Left(ConnectionError(hosts)) =>
            val connectionErrorMessage = Message(s"There was a problem with LDAP connection to: ${hosts.map(_.toString()).toList.mkString(",")}")
            Task.now(Left(DecodingFailureOps.fromError(DefinitionsLevelCreationError(connectionErrorMessage))))
          case Right(service) =>
            Task.now(Right(service))
        }
      }
    }.mapError(DefinitionsLevelCreationError.apply)

  private lazy val authorizationServiceDecoder: AsyncDecoder[LdapAuthorizationService] =
    AsyncDecoderCreator
      .instance[LdapAuthorizationService] { c =>
      val ldapServiceDecodingResult = for {
        name <- c.downField("name").as[LdapService.Name]
        connectionConfig <- connectionConfigDecoder(c)
        userSearchFiler <- userSearchFilerConfigDecoder(c)
        userGroupsSearchFilter <- userGroupsSearchFilterConfigDecoder(c)
      } yield UnboundidLdapAuthorizationService.create(
        name,
        connectionConfig,
        userSearchFiler,
        userGroupsSearchFilter
      )
      ldapServiceDecodingResult match {
        case Left(error) => Task.now(Left(error))
        case Right(task) => task.flatMap {
          case Left(ConnectionError(hosts)) =>
            val connectionErrorMessage = Message(s"There was a problem with LDAP connection to: ${hosts.map(_.toString()).toList.mkString(",")}")
            Task.now(Left(DecodingFailureOps.fromError(DefinitionsLevelCreationError(connectionErrorMessage))))
          case Right(service) =>
            Task.now(Right(service))
        }
      }
    }.mapError(DefinitionsLevelCreationError.apply)

  private val userSearchFilerConfigDecoder: Decoder[UserSearchFilterConfig] = Decoder.instance { c =>
    for {
      searchUserBaseDn <- c.downField("search_user_base_DN").as[Dn]
      userIdAttribute <- c.downNonEmptyField("user_id_attribute")
    } yield UserSearchFilterConfig(searchUserBaseDn, userIdAttribute)
  }

  // todo: implement
  private val userGroupsSearchFilterConfigDecoder: Decoder[UserGroupsSearchFilterConfig] = Decoder.failed(DecodingFailure.apply("", Nil))

  private val connectionConfigDecoder: Decoder[LdapConnectionConfig] = {
    implicit val _ = positiveValueDecoder[Int]
    Decoder
      .instance { c =>
        for {
          connectionMethod <- connectionMethodDecoder.tryDecode(c)
          poolSize <- c.downField("connection_pool_size").as[Option[Int Refined Positive]]
          connectionTimeout <- c.downFields("connection_timeout_in_sec", "connection_timeout").as[Option[FiniteDuration Refined Positive]]
          requestTimeout <- c.downFields("request_timeout_in_sec", "request_timeout").as[Option[FiniteDuration Refined Positive]]
          sslOpt <- sslSettingsDecoder.tryDecode(c)
          bindRequestUser <- bindRequestUserDecoder.tryDecode(c)
        } yield LdapConnectionConfig(
          connectionMethod,
          poolSize.getOrElse(refineV[Positive].unsafeFrom(30)),
          connectionTimeout.getOrElse(refineV[Positive].unsafeFrom(1 second)),
          requestTimeout.getOrElse(refineV[Positive].unsafeFrom(1 second)),
          sslOpt,
          bindRequestUser
        )
      }
  }

  private lazy val connectionMethodDecoder: Decoder[ConnectionMethod] =
    SyncDecoderCreator
      .instance { c =>
        for {
          hostOpt <- hostSocketAddressDecoder.tryDecode(c).map(Some.apply).recover { case _ => None }
          hostsOpt <- c.downFields("hosts", "servers").as[Option[List[SocketAddress[IpAddress]]]]
          haMethod <- c.downField("ha").as[Option[HaMethod]]
        } yield (hostOpt, hostsOpt) match {
          case (Some(host), None) =>
            Right(SingleServer(host))
          case (None, Some(hostsList)) =>
            NonEmptySet.fromSet(SortedSet.empty[SocketAddress[IpAddress]] ++ hostsList) match {
              case Some(hosts) =>
                Right(SeveralServers(hosts, haMethod.getOrElse(HaMethod.Failover)))
              case None =>
                Left(ValueLevelCreationError(Message(s"Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
            }
          case (Some(_), Some(_)) =>
            Left(ValueLevelCreationError(Message(s"Cannot accept single server settings (host,port) AND multi server configuration (servers/hosts) at the same time.")))
          case (None, None) =>
            Left(ValueLevelCreationError(Message(s"Server information missing: use either 'host' and 'port' or 'servers'/'hosts' option.")))
        }
      }
      .emapE[ConnectionMethod](identity)
      .decoder

  private implicit val haMethodDecoder: Decoder[HaMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .map(_.toUpperCase)
      .emapE[HaMethod] {
      case "FAILOVER" => Right(HaMethod.Failover)
      case "ROUND_ROBIN" => Right(HaMethod.RoundRobin)
      case unknown => Left(ValueLevelCreationError(Message(s"Unknown HA method '$unknown'")))
    }
      .decoder

  private lazy val hostSocketAddressDecoder: Decoder[SocketAddress[IpAddress]] = {
    val hostSocketAddressFromTwoFieldsDecoder =
      Decoder
        .instance { c =>
          for {
            host <- c.downField("host").as[IpAddress]
            port <- c.downField("port").as[Option[Port]]
          } yield SocketAddress(host, port.getOrElse(Port(389).get))
        }
    val hostSocketAddressFromOneFieldDecoder = Decoder.instance(_.downField("host").as[SocketAddress[IpAddress]])
    hostSocketAddressFromTwoFieldsDecoder or hostSocketAddressFromOneFieldDecoder
  }

  private lazy val sslSettingsDecoder: Decoder[Option[SslSettings]] = {
    Decoder.instance { c =>
      for {
        ssl <- c.downField("ssl_enabled").as[Option[Boolean]]
        trustAllCerts <- c.downField("ssl_trust_all_certs").as[Option[Boolean]]
      } yield ssl match {
        case Some(true) => Some(SslSettings(trustAllCerts.getOrElse(false)))
        case Some(false) | None => None
      }
    }
  }

  private implicit lazy val dnDecoder: Decoder[Dn] = DecoderHelpers.decodeStringLikeNonEmpty.map(Dn.apply)

  private lazy val bindRequestUserDecoder: Decoder[BindRequestUser] = {
    Decoder
      .instance { c =>
        for {
          dnOpt <- c.downField("bind_dn").as[Option[Dn]]
          secretOpt <- c.downField("bind_password").as[Option[String]]
        } yield (dnOpt, secretOpt)
      }
      .toSyncDecoder
      .emapE[BindRequestUser] {
      case (Some(dn), Some(secret)) => Right(BindRequestUser.CustomUser(dn, Secret(secret)))
      case (None, None) => Right(BindRequestUser.NoUser)
      case (_, _) => Left(ValueLevelCreationError(Message(s"'bind_dn' & 'bind_password' should be both present or both absent")))
    }
      .decoder
  }

}