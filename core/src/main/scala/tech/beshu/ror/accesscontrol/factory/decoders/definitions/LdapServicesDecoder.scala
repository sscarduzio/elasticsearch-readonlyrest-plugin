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
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.{HostConnectionError, ServerDiscoveryConnectionError}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.ConnectionMethod.{SeveralServers, SingleServer}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations._
import tech.beshu.ror.accesscontrol.domain.PlainTextSecret
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils._
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

import java.time.Clock
import scala.concurrent.duration._
import scala.language.postfixOps

object LdapServicesDecoder {

  implicit val nameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  def ldapServicesDefinitionsDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                     clock: Clock): AsyncDecoder[Definitions[LdapService]] = {
    AsyncDecoderCreator.instance { c =>
      DefinitionsBaseDecoder.instance[Task, LdapService]("ldaps").apply(c)
    }
  }

  private implicit def cachableLdapServiceDecoder(implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                                  clock: Clock): AsyncDecoder[LdapService] =
    AsyncDecoderCreator.instance { c =>
      val result = for {
        circuitBreakerConfig <- circuitBreakerDecoder(c)
        cacheTTl <- c.downFields("cache_ttl", "cache_ttl_in_sec").as[Option[PositiveFiniteDuration]]
      } yield {
        decodeLdapService(c, circuitBreakerConfig, cacheTTl)
      }
      result.fold(error => Task.now(Left(error)), identity)
    }

  private def decodeLdapService(cursor: HCursor,
                                circuitBreakerConfig: CircuitBreakerConfig,
                                ttl: Option[PositiveFiniteDuration])
                               (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                clock: Clock): Task[Either[DecodingFailure, LdapService]] = {
    ldapUsersServiceDecoder(circuitBreakerConfig, ttl)
      .apply(cursor)
      .flatMap {
        case Right(ldapUsersService) =>
          doDecodeLdap(cursor, ldapUsersService, circuitBreakerConfig, ttl)
        case Left(decodingFailure) =>
          Task.delay(Left(decodingFailure))
      }
  }

  private def doDecodeLdap(cursor: HCursor,
                           ldapUsersService: LdapUsersService,
                           circuitBreakerConfig: CircuitBreakerConfig,
                           ttl: Option[PositiveFiniteDuration])
                          (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                           clock: Clock): Task[Either[DecodingFailure, LdapService]] = {
    for {
      authenticationService <- (authenticationServiceDecoder(ldapUsersService, circuitBreakerConfig, ttl): AsyncDecoder[LdapAuthenticationService])(cursor)
      authorizationService <- (authorizationServiceDecoder(ldapUsersService, circuitBreakerConfig, ttl): AsyncDecoder[LdapAuthorizationService])(cursor)
    } yield (authenticationService, authorizationService) match {
      case (Right(authn), Right(authz)) => Right {
        ComposedLdapAuthService.create(ldapUsersService, authn, authz) match {
          case Right(composedService) => composedService
          case Left(message) => throw new IllegalStateException(message)
        }
      }
      case (authn@Right(_), _) => authn
      case (_, authz@Right(_)) => authz
      case (error@Left(_), _) => error
    }
  }

  private def ldapUsersServiceDecoder(circuitBreakerConfig: CircuitBreakerConfig,
                                      ttl: Option[PositiveFiniteDuration])
                                     (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): AsyncDecoder[LdapUsersService] = {
    AsyncDecoderCreator
      .instance[LdapUsersService] { c =>
        val ldapServiceDecodingResult = for {
          name <- c.downField("name").as[LdapService.Name]
          connectionConfig <- connectionConfigDecoder(c)
          userSearchFiler <- userSearchFilerConfigDecoder(c)
        } yield UnboundidLdapUsersService.create(
          name,
          ldapConnectionPoolProvider,
          connectionConfig,
          userSearchFiler
        )
        ldapServiceDecodingResult match {
          case Left(error) => Task.now(Left(error))
          case Right(task) => task.map {
            case Left(error: HostConnectionError) =>
              Left(hostConnectionErrorDecodingFailureFrom(error))
            case Left(error: ServerDiscoveryConnectionError) =>
              Left(serverDiscoveryConnectionDecodingFailureFrom(error))
            case Right(ldapUsersService) =>
              Right {
                CacheableLdapUsersServiceDecorator.create(
                  ldapUsersService = new CircuitBreakerLdapUsersServiceDecorator(ldapUsersService, circuitBreakerConfig),
                  ttl = ttl
                )
              }
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private def authenticationServiceDecoder(ldapUsersService: LdapUsersService,
                                           circuitBreakerConfig: CircuitBreakerConfig,
                                           ttl: Option[PositiveFiniteDuration])
                                          (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                           clock: Clock): AsyncDecoder[LdapAuthenticationService] =
    AsyncDecoderCreator
      .instance[LdapAuthenticationService] { c =>
        val ldapServiceDecodingResult = for {
          name <- c.downField("name").as[LdapService.Name]
          connectionConfig <- connectionConfigDecoder(c)
        } yield UnboundidLdapAuthenticationService.create(
          name,
          ldapUsersService,
          ldapConnectionPoolProvider,
          connectionConfig
        )
        ldapServiceDecodingResult match {
          case Left(error) => Task.now(Left(error))
          case Right(task) => task.map {
            case Left(error: HostConnectionError) =>
              Left(hostConnectionErrorDecodingFailureFrom(error))
            case Left(error: ServerDiscoveryConnectionError) =>
              Left(serverDiscoveryConnectionDecodingFailureFrom(error))
            case Right(ldapAuthenticationService) =>
              Right {
                CacheableLdapAuthenticationServiceDecorator.create(
                  ldapAuthenticationService = new CircuitBreakerLdapAuthenticationServiceDecorator(
                    ldapAuthenticationService, circuitBreakerConfig
                  ),
                  ttl = ttl
                )
              }
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)

  private def authorizationServiceDecoder(ldapUsersService: LdapUsersService,
                                          circuitBreakerConfig: CircuitBreakerConfig,
                                          ttl: Option[PositiveFiniteDuration])
                                         (implicit ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                          clock: Clock): AsyncDecoder[LdapAuthorizationService] =
    AsyncDecoderCreator
      .instance[LdapAuthorizationService] { c =>
        val ldapServiceDecodingResult = for {
          name <- c.downField("name").as[LdapService.Name]
          connectionConfig <- connectionConfigDecoder(c)
          userGroupsSearchFilter <- userGroupsSearchFilterConfigDecoder(c)
        } yield UnboundidLdapAuthorizationService.create(
          name, ldapUsersService, ldapConnectionPoolProvider, connectionConfig, userGroupsSearchFilter
        )
        ldapServiceDecodingResult match {
          case Left(error) =>
            Task.now(Left(error))
          case Right(task) => task.map {
            case Left(error: HostConnectionError) =>
              Left(hostConnectionErrorDecodingFailureFrom(error))
            case Left(error: ServerDiscoveryConnectionError) =>
              Left(serverDiscoveryConnectionDecodingFailureFrom(error))
            case Right(ldapAuthorizationService) =>
              Right {
                CacheableLdapAuthorizationService.create(
                  ldapService = CircuitBreakerLdapAuthorizationService.create(ldapAuthorizationService, circuitBreakerConfig),
                  ttl = ttl
                )
              }
          }
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)

  private def hostConnectionErrorDecodingFailureFrom(error: HostConnectionError) = {
    val connectionErrorMessage = Message(
      s"There was a problem with LDAP connection to: ${error.hosts.map(_.url.toString()).toList.mkString(",")}"
    )
    DecodingFailureOps.fromError(DefinitionsLevelCreationError(connectionErrorMessage))
  }

  private def serverDiscoveryConnectionDecodingFailureFrom(error: ServerDiscoveryConnectionError) = {
    val connectionErrorMessage = Message(
      s"There was a problem with LDAP connection in discovery mode. " +
        s"Connection details: recordName=${error.recordName.getOrElse("default")}, " +
        s"providerUrl=${error.providerUrl.getOrElse("default")}")
    DecodingFailureOps.fromError(DefinitionsLevelCreationError(connectionErrorMessage))
  }

  private val userSearchFilerConfigDecoder: Decoder[UserSearchFilterConfig] =
    SyncDecoderCreator
      .instance { c =>
        for {
          searchUserBaseDn <- c.downField("search_user_base_DN").as[Dn]
          userIdAttributeName <- c.downNonEmptyOptionalField("user_id_attribute")
          skipUserSearch <- c.downField("skip_user_search").as[Option[Boolean]]
        } yield {
          userIdAttributeFrom(userIdAttributeName, skipUserSearch)
            .map(UserSearchFilterConfig(searchUserBaseDn, _))
        }
      }
      .emapE[UserSearchFilterConfig](identity)
      .decoder

  private def userIdAttributeFrom(attributeName: Option[NonEmptyString],
                                  skipUserSearch: Option[Boolean]) = {
    attributeName match {
      case Some(name) if name.value.toLowerCase == "cn" =>
        skipUserSearch match {
          case Some(true) => Right(UserIdAttribute.OptimizedCn)
          case Some(false) | None => Right(UserIdAttribute.CustomAttribute(name))
        }
      case Some(_) if skipUserSearch.contains(true) => Left(DefinitionsLevelCreationError(Message(
        "When you configure 'skip_user_search: true' in the LDAP connector, the 'user_id_attribute' has to be 'cn'"
      )))
      case Some(name) => Right(UserIdAttribute.CustomAttribute(name))
      case None => Right(UserIdAttribute.CustomAttribute("uid"))
    }
  }

  private val defaultGroupsSearchModeDecoder: Decoder[UserGroupsSearchMode] =
    Decoder.instance { c =>
      for {
        searchGroupBaseDn <- c.downField("search_groups_base_DN").as[Dn]
        groupSearchFilter <- c.downField("group_search_filter").as[Option[GroupSearchFilter]]
        groupIdAttribute <- c.downField("group_name_attribute").as[Option[GroupIdAttribute]]
        uniqueMemberAttribute <- c.downField("unique_member_attribute").as[Option[UniqueMemberAttribute]]
        groupAttributeIsDN <- c.downField("group_attribute_is_dn").as[Option[Boolean]]
        serverSideGroupsFiltering <- c.downField("sever_side_groups_filtering").as[Option[Boolean]]
      } yield DefaultGroupSearch(
        searchGroupBaseDn,
        groupSearchFilter.getOrElse(GroupSearchFilter.default),
        groupIdAttribute.getOrElse(GroupIdAttribute.default),
        uniqueMemberAttribute.getOrElse(UniqueMemberAttribute.default),
        groupAttributeIsDN.getOrElse(true),
        serverSideGroupsFiltering.getOrElse(false)
      )
    }

  private val groupsFromUserAttributeModeDecoder: Decoder[UserGroupsSearchMode] =
    Decoder.instance { c =>
      for {
        searchGroupBaseDn <- c.downField("search_groups_base_DN").as[Dn]
        groupSearchFilter <- c.downField("group_search_filter").as[Option[GroupSearchFilter]]
        groupIdAttribute <- c.downField("group_name_attribute").as[Option[GroupIdAttribute]]
        groupsFromUserAttribute <- c.downField("groups_from_user_attribute").as[Option[GroupsFromUserAttribute]]
      } yield GroupsFromUserEntry(
        searchGroupBaseDn,
        groupSearchFilter.getOrElse(GroupSearchFilter.default),
        groupIdAttribute.getOrElse(GroupIdAttribute.default),
        groupsFromUserAttribute.getOrElse(GroupsFromUserAttribute.default)
      )
    }

  private val userGroupsSearchFilterConfigDecoder: Decoder[UserGroupsSearchFilterConfig] =
    Decoder.instance { c =>
      for {
        useGroupsFromUser <- c.downField("groups_from_user").as[Option[Boolean]]
        groupConfig <-
          if (useGroupsFromUser.getOrElse(false)) groupsFromUserAttributeModeDecoder.tryDecode(c)
          else defaultGroupsSearchModeDecoder.tryDecode(c)
        nestedGroupsConfig <- nestedGroupsConfigDecoder(groupConfig).tryDecode(c)
      } yield UserGroupsSearchFilterConfig(groupConfig, nestedGroupsConfig)
    }

  private val connectionConfigDecoder: Decoder[LdapConnectionConfig] = {
    implicit val positiveIntDecoder: Decoder[Int Refined Positive] = positiveValueDecoder[Int]
    Decoder
      .instance { c =>
        for {
          connectionMethod <- connectionMethodDecoder.tryDecode(c)
          poolName <- c.downField("name").as[LdapService.Name]
          poolSize <- c.downField("connection_pool_size").as[Option[Int Refined Positive]]
          connectionTimeout <- c.downFields("connection_timeout_in_sec", "connection_timeout").as[Option[PositiveFiniteDuration]]
          requestTimeout <- c.downFields("request_timeout_in_sec", "request_timeout").as[Option[PositiveFiniteDuration]]
          trustAllCertsOps <- c.downField("ssl_trust_all_certs").as[Option[Boolean]]
          ignoreLdapConnectivityProblems <- c.downField("ignore_ldap_connectivity_problems").as[Option[Boolean]]
          bindRequestUser <- bindRequestUserDecoder.tryDecode(c)
        } yield LdapConnectionConfig(
          poolName,
          connectionMethod,
          poolSize.getOrElse(refineV[Positive].unsafeFrom(30)),
          connectionTimeout.getOrElse(refineV[Positive].unsafeFrom(10 second)),
          requestTimeout.getOrElse(refineV[Positive].unsafeFrom(10 second)),
          trustAllCertsOps.getOrElse(false),
          bindRequestUser,
          ignoreLdapConnectivityProblems.getOrElse(false)
        )
      }
  }

  private lazy val circuitBreakerDecoder: Decoder[CircuitBreakerConfig] =
    SyncDecoderCreator
      .instance { c =>
        val circuitBreaker = c.downField("circuit_breaker")
        if (circuitBreaker.failed) {
          Right(defaultCircuitBreakerConfig)
        } else {
          for {
            maxRetries <- circuitBreaker.downField("max_retries").as[Int Refined Positive]
            resetDuration <- circuitBreaker.downField("reset_duration").as[PositiveFiniteDuration]
          } yield CircuitBreakerConfig(maxRetries, resetDuration)
        }
      }
      .withError(DefinitionsLevelCreationError(Message(s"At least proper values for max_retries and reset_duration are required for circuit breaker configuration")))
      .decoder

  private lazy val connectionMethodDecoder: Decoder[ConnectionMethod] =
    SyncDecoderCreator
      .instance { c =>
        for {
          hostOpt <- ldapHostDecoder.tryDecode(c).map(Some.apply).recover { case _ => None }
          hostsOpt <- c.downFields("hosts", "servers").as[Option[List[LdapHost]]]
          haMethod <- c.downField("ha").as[Option[HaMethod]]
          serverDiscovery <- c.getOrElse[Option[ConnectionMethod.ServerDiscovery]]("server_discovery")(fallback = None)
        } yield (hostOpt, hostsOpt, serverDiscovery) match {
          case (Some(host), None, None) =>
            haMethod match {
              case None =>
                Right(SingleServer(host))
              case Some(_) =>
                Left(DefinitionsLevelCreationError(Message(s"Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
            }
          case (None, Some(hostsList), None) =>
            NonEmptyList.fromList(hostsList) match {
              case Some(hosts) if allHostsWithTheSameSchema(hosts) =>
                Right(SeveralServers(hosts, haMethod.getOrElse(HaMethod.Failover)))
              case Some(_) =>
                Left(DefinitionsLevelCreationError(Message(s"The list of LDAP servers should be either all 'ldaps://' or all 'ldap://")))
              case None =>
                Left(DefinitionsLevelCreationError(Message(s"Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
            }
          case (None, None, Some(serverDiscoveryConfig)) =>
            Right(serverDiscoveryConfig)
          case (None, None, None) =>
            Left(DefinitionsLevelCreationError(Message(s"Server information missing: use 'host' and 'port', 'servers'/'hosts' or 'service_discovery' option.")))
          case _ =>
            Left(DefinitionsLevelCreationError(Message(s"Cannot accept multiple server configurations settings (host,port) or (servers/hosts) or (service_discovery) at the same time.")))
        }
      }
      .emapE[ConnectionMethod](identity)
      .decoder

  private implicit val serverDiscoveryDecoder: Decoder[Option[ConnectionMethod.ServerDiscovery]] = {
    val booleanDiscoverySettingDecoder =
      SyncDecoderCreator
        .from(Decoder.decodeBoolean)
        .map {
          case true => Option(ConnectionMethod.ServerDiscovery(None, None, None, useSSL = false))
          case false => None
        }
        .decoder

    val complexDiscoverySettingDecoder =
      SyncDecoderCreator
        .instance { c =>
          for {
            recordName <- c.downField("record_name").as[Option[String]]
            dnsUrl <- c.downField("dns_url").as[Option[String]]
            ttl <- c.downField("ttl").as[Option[PositiveFiniteDuration]]
            useSsl <- c.downField("use_ssl").as[Option[Boolean]]
          } yield ConnectionMethod.ServerDiscovery(recordName, dnsUrl, ttl, useSsl.getOrElse(false))
        }
        .map(Option.apply)
        .decoder

    booleanDiscoverySettingDecoder or complexDiscoverySettingDecoder
  }

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
          case None => Left(CoreCreationError.DefinitionsLevelCreationError(Message("Cannot parse LDAP host")))
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
            LdapHost.from(s"${if (sslEnabled) "ldaps" else "ldap"}://$host:$port")
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

  private implicit lazy val groupSearchFilterDecoder: Decoder[GroupSearchFilter] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(GroupSearchFilter.apply)

  private implicit lazy val groupIdAttributeDecoder: Decoder[GroupIdAttribute] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(GroupIdAttribute.apply)

  private implicit lazy val uniqueMemberAttributeDecoder: Decoder[UniqueMemberAttribute] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(UniqueMemberAttribute.apply)

  private implicit lazy val groupsFromUserAttributeDecoder: Decoder[GroupsFromUserAttribute] =
    DecoderHelpers.decodeStringLikeNonEmpty.map(GroupsFromUserAttribute.apply)

  private def nestedGroupsConfigDecoder(searchMode: UserGroupsSearchMode) = {
    searchMode match {
      case DefaultGroupSearch(searchGroupBaseDN, groupSearchFilter, groupIdAttribute, uniqueMemberAttribute, _, _) =>
        Decoder.instance { c =>
          for {
            nestedGroupsDepthOpt <- c.downField("nested_groups_depth").as[Option[Int Refined Positive]]
          } yield {
            nestedGroupsDepthOpt.map(nestedGroupsDepth => NestedGroupsConfig(
              nestedLevels = nestedGroupsDepth,
              searchGroupBaseDN,
              groupSearchFilter,
              uniqueMemberAttribute,
              groupIdAttribute
            ))
          }
        }
      case GroupsFromUserEntry(searchGroupBaseDN, groupSearchFilter, groupIdAttribute, _) =>
        Decoder.instance { c =>
          for {
            nestedGroupsDepthOpt <- c.downField("nested_groups_depth").as[Option[Int Refined Positive]]
            uniqueMemberAttribute <- c.downField("unique_member_attribute").as[Option[UniqueMemberAttribute]]
          } yield {
            nestedGroupsDepthOpt.map(nestedGroupsDepth => NestedGroupsConfig(
              nestedLevels = nestedGroupsDepth,
              searchGroupBaseDN,
              groupSearchFilter,
              uniqueMemberAttribute.getOrElse(UniqueMemberAttribute.default),
              groupIdAttribute
            ))
          }
        }
    }
  }

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
