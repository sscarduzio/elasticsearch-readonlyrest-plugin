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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.data.NonEmptyList
import cats.implicits._
import com.comcast.ip4s.Port
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig.ConnectionMethod.{SeveralServers, SingleServer}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{DefaultGroupSearch, GroupsFromUserAttribute}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.domain.PlainTextSecret
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils._
import tech.beshu.ror.boot.RorSchedulers

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

object LdapServicesDecoder {

  implicit val nameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  def ldapServicesDefinitionsDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): AsyncDecoder[Definitions[LdapService]] = {
    AsyncDecoderCreator.instance { c =>
      DefinitionsBaseDecoder.instance[Task, LdapService]("ldaps").apply(c)
    }
  }

  private implicit def cachableLdapServiceDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): AsyncDecoder[LdapService] =
    AsyncDecoderCreator.instance { c =>
     c.downFields("cache_ttl_in_sec", "cache_ttl")
        .as[Option[FiniteDuration Refined Positive]]
        .map {
          case Some(ttl) =>
          decodeLdapService(c).map(_.map {
            case service: LdapAuthService => new CacheableLdapServiceDecorator(service, ttl)
            case service: LdapAuthenticationService => new CacheableLdapAuthenticationServiceDecorator(service, ttl)
            case service: LdapAuthorizationService => new CacheableLdapAuthorizationServiceDecorator(service, ttl)
          })
          case None =>
            decodeLdapService(c)
        }
        .fold(error => Task.now(Left(error)), identity)
    }

  private def decodeLdapService(cursor: HCursor)
                               (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): Task[Either[DecodingFailure, LdapUserService]] = {
    for {
      authenticationService <- (authenticationServiceDecoder: AsyncDecoder[LdapAuthenticationService])(cursor)
      authortizationService <- (authorizationServiceDecoder: AsyncDecoder[LdapAuthorizationService])(cursor)
    } yield (authenticationService, authortizationService) match {
      case (Right(authn), Right(authz)) => Right {
        new ComposedLdapAuthService(authn.id, authn, authz)
      }
      case (authn@Right(_), _) => authn
      case (_, authz@Right(_)) => authz
      case (error@Left(_), _) => error
    }
  }

  private def authenticationServiceDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): AsyncDecoder[LdapAuthenticationService] =
    AsyncDecoderCreator
      .instance[LdapAuthenticationService] { c =>
      val ldapServiceDecodingResult = for {
        name <- c.downField("name").as[LdapService.Name]
        connectionConfig <- connectionConfigDecoder(c)
        userSearchFiler <- userSearchFilerConfigDecoder(c)
      } yield UnboundidLdapAuthenticationService.create(
        name,
        ldapConnectionPoolProvider,
        connectionConfig,
        userSearchFiler,
        RorSchedulers.ldapUnboundIdBlockingScheduler
      )
      ldapServiceDecodingResult match {
        case Left(error) => Task.now(Left(error))
        case Right(task) => task.flatMap {
          case Left(ConnectionError(hosts)) =>
            val connectionErrorMessage = Message(s"There was a problem with LDAP connection to: ${hosts.map(_.url.toString()).toList.mkString(",")}")
            Task.now(Left(DecodingFailureOps.fromError(DefinitionsLevelCreationError(connectionErrorMessage))))
          case Right(service) =>
            Task.now(Right(service))
        }
      }
    }.mapError(DefinitionsLevelCreationError.apply)

  private def authorizationServiceDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): AsyncDecoder[LdapAuthorizationService] =
    AsyncDecoderCreator
      .instance[LdapAuthorizationService] { c =>
      val ldapServiceDecodingResult = for {
        name <- c.downField("name").as[LdapService.Name]
        connectionConfig <- connectionConfigDecoder(c)
        userSearchFiler <- userSearchFilerConfigDecoder(c)
        userGroupsSearchFilter <- userGroupsSearchFilterConfigDecoder(c)
      } yield UnboundidLdapAuthorizationService.create(
        name,
        ldapConnectionPoolProvider,
        connectionConfig,
        userSearchFiler,
        userGroupsSearchFilter,
        RorSchedulers.ldapUnboundIdBlockingScheduler
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
      userIdAttribute <- c.downNonEmptyOptionalField("user_id_attribute")
    } yield UserSearchFilterConfig(
      searchUserBaseDn,
      userIdAttribute.getOrElse(NonEmptyString.unsafeFrom("uid"))
    )
  }

  private val defaultGroupsSearchModeDecoder: Decoder[UserGroupsSearchMode] =
    Decoder.instance { c =>
      for {
        searchGroupBaseDn <- c.downField("search_groups_base_DN").as[Dn]
        groupNameAttribute <- c.downNonEmptyOptionalField("group_name_attribute")
        uniqueMemberAttribute <- c.downNonEmptyOptionalField("unique_member_attribute")
        groupSearchFilter <- c.downNonEmptyOptionalField("group_search_filter")
        groupAttributeIsDN <- c.downField("group_attribute_is_dn").as[Option[Boolean]]
      } yield DefaultGroupSearch(
        searchGroupBaseDn,
        groupNameAttribute.getOrElse(NonEmptyString.unsafeFrom("cn")),
        uniqueMemberAttribute.getOrElse(NonEmptyString.unsafeFrom("uniqueMember")),
        groupSearchFilter.getOrElse(NonEmptyString.unsafeFrom("(cn=*)")),
        groupAttributeIsDN.getOrElse(true)
      )
    }

  private val groupsFromUserAttributeModeDecoder: Decoder[UserGroupsSearchMode] =
    Decoder.instance { c =>
      for {
        searchGroupBaseDn <- c.downField("search_groups_base_DN").as[Dn]
        groupNameAttribute <- c.downNonEmptyOptionalField("group_name_attribute")
        groupsFromUserAttribute <- c.downNonEmptyOptionalField("groups_from_user_attribute")
      } yield GroupsFromUserAttribute(
        searchGroupBaseDn,
        groupNameAttribute.getOrElse(NonEmptyString.unsafeFrom("cn")),
        groupsFromUserAttribute.getOrElse(NonEmptyString.unsafeFrom("memberOf"))
      )
    }

  private val userGroupsSearchFilterConfigDecoder: Decoder[UserGroupsSearchFilterConfig] =
    Decoder.instance { c =>
      for {
        useGroupsFromUser <- c.downField("groups_from_user").as[Option[Boolean]]
        config <-
          if (useGroupsFromUser.getOrElse(false)) groupsFromUserAttributeModeDecoder.tryDecode(c)
          else defaultGroupsSearchModeDecoder.tryDecode(c)
      } yield UserGroupsSearchFilterConfig(config)
    }

  private val connectionConfigDecoder: Decoder[LdapConnectionConfig] = {
    implicit val _ = positiveValueDecoder[Int]
    Decoder
      .instance { c =>
        for {
          connectionMethod <- connectionMethodDecoder.tryDecode(c)
          poolSize <- c.downField("connection_pool_size").as[Option[Int Refined Positive]]
          connectionTimeout <- c.downFields("connection_timeout_in_sec", "connection_timeout").as[Option[FiniteDuration Refined Positive]]
          requestTimeout <- c.downFields("request_timeout_in_sec", "request_timeout").as[Option[FiniteDuration Refined Positive]]
          trustAllCertsOps <- c.downField("ssl_trust_all_certs").as[Option[Boolean]]
          bindRequestUser <- bindRequestUserDecoder.tryDecode(c)
        } yield LdapConnectionConfig(
          connectionMethod,
          poolSize.getOrElse(refineV[Positive].unsafeFrom(30)),
          connectionTimeout.getOrElse(refineV[Positive].unsafeFrom(10 second)),
          requestTimeout.getOrElse(refineV[Positive].unsafeFrom(10 second)),
          trustAllCertsOps.getOrElse(false),
          bindRequestUser
        )
      }
  }

  private lazy val connectionMethodDecoder: Decoder[ConnectionMethod] =
    SyncDecoderCreator
      .instance { c =>
        for {
          hostOpt <- ldapHostDecoder.tryDecode(c).map(Some.apply).recover { case _ => None }
          hostsOpt <- c.downFields("hosts", "servers").as[Option[List[LdapHost]]]
          haMethod <- c.downField("ha").as[Option[HaMethod]]
        } yield (hostOpt, hostsOpt) match {
          case (Some(host), None) =>
            haMethod match {
              case None =>
                Right(SingleServer(host))
              case Some(_) =>
                Left(DefinitionsLevelCreationError(Message(s"Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
            }
          case (None, Some(hostsList)) =>
            NonEmptyList.fromList(hostsList) match {
              case Some(hosts) if allHostsWithTheSameSchema(hosts) =>
                Right(SeveralServers(hosts, haMethod.getOrElse(HaMethod.Failover)))
              case Some(_) =>
                Left(DefinitionsLevelCreationError(Message(s"The list of LDAP servers should be either all 'ldaps://' or all 'ldap://")))
              case None =>
                Left(DefinitionsLevelCreationError(Message(s"Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
            }
          case (Some(_), Some(_)) =>
            Left(DefinitionsLevelCreationError(Message(s"Cannot accept single server settings (host,port) AND multi server configuration (servers/hosts) at the same time.")))
          case (None, None) =>
            Left(DefinitionsLevelCreationError(Message(s"Server information missing: use either 'host' and 'port' or 'servers'/'hosts' option.")))
        }
      }
      .emapE[ConnectionMethod](identity)
      .decoder

  private def allHostsWithTheSameSchema(hosts: NonEmptyList[LdapHost]) = hosts.map(_.isSecure).distinct.length == 1

  private implicit val haMethodDecoder: Decoder[HaMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .map(_.toUpperCase)
      .emapE[HaMethod] {
      case "FAILOVER" => Right(HaMethod.Failover)
      case "ROUND_ROBIN" => Right(HaMethod.RoundRobin)
      case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown HA method '$unknown'")))
    }
      .decoder

  private implicit lazy val ldapHostDecoder: Decoder[LdapHost] = {
    def withLdapHostCreationError(decoder: Decoder[Option[LdapHost]]): Decoder[LdapHost] = {
      decoder
        .toSyncDecoder
        .emapE {
          case Some(host) => Right(host)
          case None => Left(AclCreationError.DefinitionsLevelCreationError(Message("Cannot parse LDAP host")))
        }
        .decoder
    }

    val ldapHostFromTwoFieldsDecoder = withLdapHostCreationError {
      Decoder
        .instance { c =>
          for {
            host <- c.downField("host").as[String]
            portOpt <- c.downField("port").as[Option[Port]]
            sslEnabledOpt <- c.downField("ssl_enabled").as[Option[Boolean]]
          } yield {
            val sslEnabled = sslEnabledOpt.getOrElse(true)
            val port = portOpt.getOrElse(Port(389).get)
            LdapHost.from(s"${if(sslEnabled) "ldaps" else "ldap"}://$host:$port")
          }
        }
    }
    val hostSocketAddressFromOneFieldDecoder = withLdapHostCreationError {
      Decoder
        .decodeString
        .map(LdapHost.from)
    }
    ldapHostFromTwoFieldsDecoder or hostSocketAddressFromOneFieldDecoder
  }

  private implicit lazy val dnDecoder: Decoder[Dn] = DecoderHelpers.decodeStringLikeNonEmpty.map(Dn.apply)

  private lazy val bindRequestUserDecoder: Decoder[BindRequestUser] = {
    Decoder
      .instance { c =>
        for {
          dnOpt <- c.downField("bind_dn").as[Option[Dn]]
          secretOpt <- c.downField("bind_password").as[Option[NonEmptyString]]
        } yield (dnOpt, secretOpt)
      }
      .toSyncDecoder
      .emapE[BindRequestUser] {
      case (Some(dn), Some(secret)) => Right(BindRequestUser.CustomUser(dn, PlainTextSecret(secret)))
      case (None, None) => Right(BindRequestUser.Anonymous)
      case (_, _) => Left(DefinitionsLevelCreationError(Message(s"'bind_dn' & 'bind_password' should be both present or both absent")))
    }
      .decoder
  }

}
