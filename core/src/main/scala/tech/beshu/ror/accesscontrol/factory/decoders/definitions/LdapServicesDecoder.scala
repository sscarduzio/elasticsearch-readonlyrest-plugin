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

import cats.data.{EitherT, NonEmptyList}
import com.comcast.ip4s.Port
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, DecodingFailure, HCursor}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.ConnectionError.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.LdapConnectionConfig.ConnectionMethod.{SeveralServers, SingleServer}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.domain.PlainTextSecret
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.utils.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.value

import java.time.Clock
import java.util.concurrent.TimeUnit
import scala.language.postfixOps

object LdapServicesDecoder extends Logging {

  given nameDecoder: Decoder[LdapService.Name] = DecoderHelpers.decodeNonEmptyStringField.map(LdapService.Name.apply)

  def ldapServicesDefinitionsDecoder(using UnboundidLdapConnectionPoolProvider, Clock): AsyncDecoder[Definitions[LdapService]] = {
    AsyncDecoderCreator.instance { c =>
      DefinitionsBaseDecoder.instance[Task, LdapService]("ldaps").apply(c)
    }
  }

  private given ldapServiceDecoder(using UnboundidLdapConnectionPoolProvider, Clock): AsyncDecoder[LdapService] = {
    AsyncDecoderCreator
      .instance { c =>
        value {
          for {
            serviceName <- c.downFieldAs[LdapService.Name]("name").toEitherT[Task]
            circuitBreakerConfig <- c.as[CircuitBreakerConfig].toEitherT[Task]
            cacheTTl <- c.downFieldsAs[Option[PositiveFiniteDuration]]("cache_ttl", "cache_ttl_in_sec").toEitherT[Task]
            connectionConfig <- connectionConfigDecoder(serviceName)(c).toEitherT[Task]
            ldapService <- decodeLdapService(c, serviceName, connectionConfig, circuitBreakerConfig, cacheTTl)
          } yield ldapService
        }
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private def decodeLdapService(cursor: HCursor,
                                serviceName: LdapService.Name,
                                connectionConfig: LdapConnectionConfig,
                                circuitBreakerConfig: CircuitBreakerConfig,
                                ttl: Option[PositiveFiniteDuration])
                               (using UnboundidLdapConnectionPoolProvider, Clock): EitherT[Task, DecodingFailure, LdapService] = {
    cursor.downField("users").success match {
      case Some(usersCursor) =>
        // new format
        for {
          userSearchFilter <- usersCursor.as[UserSearchFilterConfig].toEitherT[Task]
          ldapUsersService <- createLdapUsersService(serviceName, connectionConfig, userSearchFilter, circuitBreakerConfig, ttl)
          ldapAuthenticationService <- createLdapAuthenticationService(serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl)
          maybeLdapAuthorizationService <- decodeOptionalLdapAuthorizationService(cursor, serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl)
        } yield maybeLdapAuthorizationService match {
          case Some(ldapAuthorizationService) =>
            createComposedLdapService(ldapUsersService, ldapAuthenticationService, ldapAuthorizationService)
          case None =>
            ldapAuthenticationService
        }
      case None =>
        // old format
        for {
          userSearchFilter <- cursor.as[UserSearchFilterConfig].toEitherT[Task]
          ldapUsersService <- createLdapUsersService(serviceName, connectionConfig, userSearchFilter, circuitBreakerConfig, ttl)
          ldapService <- deprecatedDecodeLdapService(cursor, serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl)
        } yield ldapService
    }
  }


  private def createLdapUsersService(serviceName: LdapService.Name,
                                     connectionConfig: LdapConnectionConfig,
                                     userSearchFilter: UserSearchFilterConfig,
                                     circuitBreakerConfig: CircuitBreakerConfig,
                                     ttl: Option[PositiveFiniteDuration]
                                    )(using UnboundidLdapConnectionPoolProvider): EitherT[Task, DecodingFailure, LdapUsersService] = {
    EitherT(
      UnboundidLdapUsersService.create(
        serviceName,
        summon[UnboundidLdapConnectionPoolProvider],
        connectionConfig,
        userSearchFilter
      )
    )
      .leftMap(connectionErrorDecodingFailureFrom)
      .map { ldapUsersService =>
        CacheableLdapUsersServiceDecorator.create(
          ldapUsersService = new CircuitBreakerLdapUsersServiceDecorator(ldapUsersService, circuitBreakerConfig),
          ttl = ttl
        )
      }
  }

  private def createLdapAuthenticationService(serviceName: LdapService.Name,
                                              ldapUsersService: LdapUsersService,
                                              connectionConfig: LdapConnectionConfig,
                                              circuitBreakerConfig: CircuitBreakerConfig,
                                              ttl: Option[PositiveFiniteDuration])
                                             (using UnboundidLdapConnectionPoolProvider, Clock) = {
    EitherT(
      UnboundidLdapAuthenticationService.create(
        serviceName,
        ldapUsersService,
        summon[UnboundidLdapConnectionPoolProvider],
        connectionConfig
      )
    )
      .leftMap(connectionErrorDecodingFailureFrom)
      .map { ldapAuthenticationService =>
        CacheableLdapAuthenticationServiceDecorator.create(
          ldapAuthenticationService = new CircuitBreakerLdapAuthenticationServiceDecorator(
            ldapAuthenticationService, circuitBreakerConfig
          ),
          ttl = ttl
        )
      }
  }

  private def decodeOptionalLdapAuthorizationService(cursor: HCursor,
                                                     serviceName: LdapService.Name,
                                                     ldapUsersService: LdapUsersService,
                                                     connectionConfig: LdapConnectionConfig,
                                                     circuitBreakerConfig: CircuitBreakerConfig,
                                                     ttl: Option[PositiveFiniteDuration])
                                                    (using UnboundidLdapConnectionPoolProvider, Clock): EitherT[Task, DecodingFailure, Option[LdapAuthorizationService]] = {
    cursor
      .downField("groups")
      .success
      .map { groupsCursor =>
        decodeLdapAuthorizationService(groupsCursor, serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl)
      }
      .sequence
  }

  private def decodeLdapAuthorizationService(cursor: HCursor,
                                             serviceName: LdapService.Name,
                                             ldapUsersService: LdapUsersService,
                                             connectionConfig: LdapConnectionConfig,
                                             circuitBreakerConfig: CircuitBreakerConfig,
                                             ttl: Option[PositiveFiniteDuration])
                                            (using UnboundidLdapConnectionPoolProvider, Clock): EitherT[Task, DecodingFailure, LdapAuthorizationService] = {
    for {
      userGroupsSearchFilter <- userGroupsSearchFilterConfigDecoder(serviceName)(cursor).toEitherT[Task]
      ldapAuthorizationService <- createLdapAuthorizationService(
        serviceName,
        ldapUsersService,
        connectionConfig,
        userGroupsSearchFilter,
        circuitBreakerConfig,
        ttl
      )
    } yield ldapAuthorizationService
  }

  private def createLdapAuthorizationService(serviceName: LdapService.Name,
                                             ldapUsersService: LdapUsersService,
                                             connectionConfig: LdapConnectionConfig,
                                             userGroupsSearchFilter: UserGroupsSearchFilterConfig,
                                             circuitBreakerConfig: CircuitBreakerConfig,
                                             ttl: Option[PositiveFiniteDuration]
                                            )(using UnboundidLdapConnectionPoolProvider, Clock): EitherT[Task, DecodingFailure, LdapAuthorizationService] = {
    EitherT(
      UnboundidLdapAuthorizationService.create(
        serviceName,
        ldapUsersService,
        summon[UnboundidLdapConnectionPoolProvider],
        connectionConfig,
        userGroupsSearchFilter
      )
    )
      .leftMap(connectionErrorDecodingFailureFrom)
      .map { ldapAuthorizationService =>
        CacheableLdapAuthorizationService.create(
          ldapService = CircuitBreakerLdapAuthorizationService.create(ldapAuthorizationService, circuitBreakerConfig),
          ttl = ttl
        )
      }
  }

  private def createComposedLdapService(ldapUsersService: LdapUsersService, authn: LdapAuthenticationService, authz: LdapAuthorizationService) = {
    ComposedLdapAuthService.create(ldapUsersService, authn, authz) match {
      case Right(composedService) => composedService
      case Left(message) => throw new IllegalStateException(message)
    }
  }

  private def deprecatedDecodeLdapService(cursor: HCursor,
                                          serviceName: LdapService.Name,
                                          ldapUsersService: LdapUsersService,
                                          connectionConfig: LdapConnectionConfig,
                                          circuitBreakerConfig: CircuitBreakerConfig,
                                          ttl: Option[PositiveFiniteDuration])
                                         (using UnboundidLdapConnectionPoolProvider, Clock): EitherT[Task, DecodingFailure, LdapService] = EitherT {
    for {
      authenticationServiceOrError <- createLdapAuthenticationService(serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl).value
      authorizationServiceOrError <- decodeLdapAuthorizationService(cursor, serviceName, ldapUsersService, connectionConfig, circuitBreakerConfig, ttl).value
    } yield (authenticationServiceOrError, authorizationServiceOrError) match {
      // We don't know the user's intention of what service he would like to create
      // this method based on decoding failure may cause the start of service with a different type than expected
      // method is left for backward compatibility
      case (Right(authn), Right(authz)) => Right(createComposedLdapService(ldapUsersService, authn, authz))
      case (authn@Right(_), _) => authn
      case (_, authz@Right(_)) => authz
      case (error@Left(_), _) => error
    }
  }

  private def connectionErrorDecodingFailureFrom(error: ConnectionError) = {
    def connectionErrorFrom(message: String) =
      DecodingFailureOps.fromError(DefinitionsLevelCreationError(Message(message)))

    error match
      case CannotConnectError(ldap, connectionMethod) =>
        connectionErrorFrom(
          connectionMethod match {
            case SingleServer(host) =>
              s"There was a problem with '${ldap.show}' LDAP connection to: ${host.show}"
            case SeveralServers(hosts, _) =>
              s"There was a problem with '${ldap.show}' LDAP connection to: ${hosts.toList.show}"
            case ConnectionMethod.ServerDiscovery(recordName, providerUrl, _, _) =>
              s"There was a problem with '${ldap.show}' LDAP connection in discovery mode. " +
                s"Connection details: recordName=${recordName.getOrElse("default").show}, " +
                s"providerUrl=${providerUrl.getOrElse("default")}"
          }
        )
      case HostResolvingError(ldap, hosts) =>
        connectionErrorFrom(s"There was a problem with resolving '${ldap.show}' LDAP hosts: ${hosts.toList.show}")
      case BindingTestError(ldap) =>
        connectionErrorFrom(s"There was a problem with test binding in case of '${ldap.show}' LDAP connector}")
      case UnexpectedConnectionError(ldap, cause) =>
        logger.error(s"Unexpected '${ldap.show}' LDAP connection error", cause)
        connectionErrorFrom(s"Unexpected '${ldap.show}' LDAP connection error: '${cause.getMessage.show}'}")
  }

  private given Decoder[UserSearchFilterConfig] =
    SyncDecoderCreator
      .instance { c =>
        for {
          searchUserBaseDn <- c.downFieldAs[Dn]("search_user_base_DN")
          userIdAttributeName <- c.downNonEmptyOptionalField("user_id_attribute")
          skipUserSearch <- c.downFieldAs[Option[Boolean]]("skip_user_search")
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
      case None => Right(UserIdAttribute.CustomAttribute(nes("uid")))
    }
  }

  private val defaultGroupsSearchModeDecoder: Decoder[UserGroupsSearchMode] =
    SyncDecoderCreator.instance { c =>
      for {
        searchGroupBaseDn <- c.downFieldAs[Dn]("search_groups_base_DN")
        groupSearchFilter <- c.downFieldAs[Option[GroupSearchFilter]]("group_search_filter")
        maybeGroupIdAttribute <- c.downFieldAs[Option[GroupIdAttribute]]("group_id_attribute")
        maybeGroupNameAttribute <- c.downFieldAs[Option[GroupNameAttribute]]("group_name_attribute")
        uniqueMemberAttribute <- c.downFieldAs[Option[UniqueMemberAttribute]]("unique_member_attribute")
        groupAttributeIsDN <- c.downFieldAs[Option[Boolean]]("group_attribute_is_dn")
        serverSideGroupsFiltering <- c.downFieldsAs[Option[Boolean]]("server_side_groups_filtering", "sever_side_groups_filtering")
      } yield {
        val groupAttribute = (maybeGroupIdAttribute, maybeGroupNameAttribute) match {
          case (Some(id), Some(name)) => GroupAttribute(id, name)
          case (Some(id), None) => GroupAttribute(id, GroupNameAttribute.from(id))
          case (None, Some(name)) =>
            // When only group_name_attribute is defined, we treat it as group ID (backward compatibility)
            GroupAttribute(GroupIdAttribute(name.value), name)
          case (None, None) => GroupAttribute(GroupIdAttribute.default, GroupNameAttribute.from(GroupIdAttribute.default))
        }
        DefaultGroupSearch(
          searchGroupBaseDn,
          groupSearchFilter.getOrElse(GroupSearchFilter.default),
          groupAttribute,
          uniqueMemberAttribute.getOrElse(UniqueMemberAttribute.default),
          groupAttributeIsDN.getOrElse(true),
          serverSideGroupsFiltering.getOrElse(false)
        ): UserGroupsSearchMode
      }
    }.decoder

  private def groupsFromUserAttributeModeDecoder(serviceName: LdapService.Name): Decoder[UserGroupsSearchMode] =
    SyncDecoderCreator.instance { c =>
        for {
          searchGroupBaseDn <- c.downFieldAs[Dn]("search_groups_base_DN")
          groupSearchFilter <- c.downFieldAs[Option[GroupSearchFilter]]("group_search_filter")
          maybeGroupIdAttribute <- c.downFieldAs[Option[GroupIdAttribute]]("group_id_attribute")
          maybeGroupNameAttribute <- c.downFieldAs[Option[GroupNameAttribute]]("group_name_attribute")
          groupsFromUserAttribute <- c.downFieldAs[Option[GroupsFromUserAttribute]]("groups_from_user_attribute")
          groupIdAttribute <- (maybeGroupIdAttribute, maybeGroupNameAttribute) match {
            case (Some(id), Some(_)) =>
              Left(DecodingFailureOps.fromError(DefinitionsLevelCreationError(Message(s"Group names (group_name_attribute) are not supported when the group search in user entries is used [ldap ${serviceName.show}]. If you intend to use this feature, please get in touch with us."))))
            case (Some(id), None) => Right(id)
            case (None, Some(name)) =>
              // When only group_name_attribute is defined, we treat it as group ID (backward compatibility)
              Right(GroupIdAttribute(name.value))
            case (None, None) => Right(GroupIdAttribute.default)
          }
        } yield {
          GroupsFromUserEntry(
            searchGroupBaseDn,
            groupSearchFilter.getOrElse(GroupSearchFilter.default),
            groupIdAttribute,
            groupsFromUserAttribute.getOrElse(GroupsFromUserAttribute.default)
          )
        }
      }
      .decoder

  private def userGroupsSearchFilterConfigDecoder(serviceName: LdapService.Name): Decoder[UserGroupsSearchFilterConfig] =
    Decoder.instance { c =>
      for {
        deprecatedGroupsSearchMode <-
          c.downFieldAs[Option[Boolean]]("groups_from_user")
            .map {
              _.map {
                case true => GroupsSearchMode.SearchInUserEntries
                case false => GroupsSearchMode.SearchInGroupEntries
              }
            }
        groupsSearchMode <- c.downFieldAs[Option[GroupsSearchMode]]("mode")
        groupConfig <- (groupsSearchMode, deprecatedGroupsSearchMode) match {
          case (Some(_), Some(_)) => Left(DecodingFailureOps.fromError(
            DefinitionsLevelCreationError(Message("Cannot accept groups search attributes groups_from_user/mode at the same time"))
          ))
          case (maybeSearchMode, maybeDeprecatedSearchMode) =>
            maybeSearchMode.orElse(maybeDeprecatedSearchMode).getOrElse(GroupsSearchMode.SearchInGroupEntries) match
              case GroupsSearchMode.SearchInUserEntries =>
                groupsFromUserAttributeModeDecoder(serviceName)(c)
              case GroupsSearchMode.SearchInGroupEntries =>
                defaultGroupsSearchModeDecoder(c)
        }
        nestedGroupsConfig <- nestedGroupsConfigDecoder(groupConfig)(c)
      } yield UserGroupsSearchFilterConfig(groupConfig, nestedGroupsConfig)
    }

  private def connectionConfigDecoder(serviceName: LdapService.Name): Decoder[LdapConnectionConfig] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          connectionMethod <- c.as[ConnectionMethod]
          poolSize <- c.downFieldAs[Option[Int Refined Positive]]("connection_pool_size")
          connectionTimeout <- c.downFieldsAs[Option[PositiveFiniteDuration]]("connection_timeout_in_sec", "connection_timeout")
          requestTimeout <- c.downFieldsAs[Option[PositiveFiniteDuration]]("request_timeout_in_sec", "request_timeout")
          trustAllCertsOps <- c.downFieldAs[Option[Boolean]]("ssl_trust_all_certs")
          ignoreLdapConnectivityProblems <- c.downFieldAs[Option[Boolean]]("ignore_ldap_connectivity_problems")
          bindRequestUser <- c.as[BindRequestUser]
        } yield LdapConnectionConfig(
          serviceName,
          connectionMethod,
          poolSize.getOrElse(positiveInt(30)),
          connectionTimeout.getOrElse(positiveFiniteDuration(10, TimeUnit.SECONDS)),
          requestTimeout.getOrElse(positiveFiniteDuration(10, TimeUnit.SECONDS)),
          trustAllCertsOps.getOrElse(false),
          bindRequestUser,
          ignoreLdapConnectivityProblems.getOrElse(false)
        )
      }
      .decoder
  }

  private given Decoder[CircuitBreakerConfig] =
    SyncDecoderCreator
      .instance { c =>
        c
          .downField("circuit_breaker")
          .success
          .map { circuitBreakerCursor =>
            for {
              maxRetries <- circuitBreakerCursor.downFieldAs[Int Refined Positive]("max_retries")
              resetDuration <- circuitBreakerCursor.downFieldAs[PositiveFiniteDuration]("reset_duration")
            } yield CircuitBreakerConfig(maxRetries, resetDuration)
          }
          .getOrElse(Right(defaultCircuitBreakerConfig))
      }
      .withError(DefinitionsLevelCreationError(Message(s"At least proper values for max_retries and reset_duration are required for circuit breaker configuration")))
      .decoder

  private given Decoder[ConnectionMethod] =
    SyncDecoderCreator
      .instance { c =>
        for {
          hostOpt <- Decoder[LdapHost].apply(c).map(Some.apply).recover { case _ => None }
          hostsOpt <- c.downFieldsAs[Option[List[LdapHost]]]("hosts", "servers")
          haMethod <- c.downFieldAs[Option[HaMethod]]("ha")
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

  private given Decoder[Option[ConnectionMethod.ServerDiscovery]] = {
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
            recordName <- c.downFieldAs[Option[String]]("record_name")
            dnsUrl <- c.downFieldAs[Option[String]]("dns_url")
            ttl <- c.downFieldAs[Option[PositiveFiniteDuration]]("ttl")
            useSsl <- c.downFieldAs[Option[Boolean]]("use_ssl")
          } yield ConnectionMethod.ServerDiscovery(recordName, dnsUrl, ttl, useSsl.getOrElse(false))
        }
        .map(Option.apply)
        .decoder

    booleanDiscoverySettingDecoder or complexDiscoverySettingDecoder
  }

  private def allHostsWithTheSameSchema(hosts: NonEmptyList[LdapHost]) = hosts.map(_.isSecure).distinct.length == 1

  private given Decoder[HaMethod] =
    SyncDecoderCreator
      .from(Decoder.decodeString)
      .map(_.toUpperCase)
      .emapE[HaMethod] {
        case "FAILOVER" => Right(HaMethod.Failover)
        case "ROUND_ROBIN" => Right(HaMethod.RoundRobin)
        case unknown => Left(DefinitionsLevelCreationError(Message(s"Unknown HA method '${unknown.show}'")))
      }
      .decoder

  private given Decoder[LdapHost] = {
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
            host <- c.downFieldAs[String]("host")
            portOpt <- c.downFieldAs[Option[Port]]("port")
            sslEnabledOpt <- c.downFieldAs[Option[Boolean]]("ssl_enabled")
          } yield {
            val sslEnabled = sslEnabledOpt.getOrElse(true)
            val port = portOpt.getOrElse(Port.fromInt(389).get)
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

  private given Decoder[Dn] = DecoderHelpers.decodeNonEmptyStringField.map(Dn.apply)

  private given Decoder[GroupSearchFilter] =
    DecoderHelpers.decodeNonEmptyStringField.map(GroupSearchFilter.apply)

  private given Decoder[GroupIdAttribute] =
    DecoderHelpers.decodeNonEmptyStringField.map(GroupIdAttribute.apply)

  private given Decoder[GroupNameAttribute] =
    DecoderHelpers.decodeNonEmptyStringField.map(GroupNameAttribute.apply)

  private given Decoder[UniqueMemberAttribute] =
    DecoderHelpers.decodeNonEmptyStringField.map(UniqueMemberAttribute.apply)

  private given Decoder[GroupsFromUserAttribute] =
    DecoderHelpers.decodeNonEmptyStringField.map(GroupsFromUserAttribute.apply)

  private def nestedGroupsConfigDecoder(searchMode: UserGroupsSearchMode) = {
    searchMode match {
      case DefaultGroupSearch(searchGroupBaseDN, groupSearchFilter, groupAttribute, uniqueMemberAttribute, _, _) =>
        Decoder.instance { c =>
          for {
            nestedGroupsDepthOpt <- c.downFieldAs[Option[Int Refined Positive]]("nested_groups_depth")
          } yield {
            nestedGroupsDepthOpt.map(nestedGroupsDepth => NestedGroupsConfig(
              nestedLevels = nestedGroupsDepth,
              searchGroupBaseDN,
              groupSearchFilter,
              uniqueMemberAttribute,
              groupAttribute
            ))
          }
        }
      case GroupsFromUserEntry(searchGroupBaseDN, groupSearchFilter, groupIdAttribute, _) =>
        Decoder.instance { c =>
          for {
            nestedGroupsDepthOpt <- c.downFieldAs[Option[Int Refined Positive]]("nested_groups_depth")
            uniqueMemberAttribute <- c.downFieldAs[Option[UniqueMemberAttribute]]("unique_member_attribute")
          } yield {
            nestedGroupsDepthOpt.map(nestedGroupsDepth => NestedGroupsConfig(
              nestedLevels = nestedGroupsDepth,
              searchGroupBaseDN,
              groupSearchFilter,
              uniqueMemberAttribute.getOrElse(UniqueMemberAttribute.default),
              GroupAttribute(groupIdAttribute, GroupNameAttribute.from(groupIdAttribute))
            ))
          }
        }
    }
  }

  private given Decoder[BindRequestUser] = {
    Decoder
      .instance { c =>
        for {
          dnOpt <- c.downFieldAs[Option[Dn]]("bind_dn")
          secretOpt <- c.downFieldAs[Option[NonEmptyString]]("bind_password")
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

  private sealed trait GroupsSearchMode
  private object GroupsSearchMode {
    case object SearchInUserEntries extends GroupsSearchMode
    case object SearchInGroupEntries extends GroupsSearchMode

    given Decoder[GroupsSearchMode] =
      Decoder
        .decodeString
        .toSyncDecoder
        .emapE[GroupsSearchMode] {
          case "search_groups_in_user_entries" => Right(SearchInUserEntries)
          case "search_groups_in_group_entries" => Right(SearchInGroupEntries)
          case other => Left(DefinitionsLevelCreationError(Message(
            s"Unknown mode of groups search: ${other.show}. Supported modes are search_groups_in_user_entries, search_groups_in_group_entries"
          )))

        }.decoder
  }

}
