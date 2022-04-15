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
package tech.beshu.ror.accesscontrol.factory

import cats.data.{NonEmptyList, State, Validated}
import cats.implicits._
import cats.kernel.Monoid
import io.circe._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol._
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.acl.AccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.{RuleWithVariableUsageDefinition, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Header, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.UsernameCaseMapping
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.{AclCreationError, Attributes}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions._
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.{AuditingSettingsDecoder, GlobalStaticSettingsDecoder}
import tech.beshu.ror.accesscontrol.logging.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, DecodingFailureOps, _}
import tech.beshu.ror.accesscontrol.utils._
import tech.beshu.ror.boot.ReadonlyRest.RorMode
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.providers.{EnvVarsProvider, UuidProvider}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.yaml.YamlOps

import java.time.Clock

final case class CoreSettings(aclEngine: AccessControl,
                              auditingSettings: Option[AuditingTool.Settings])

trait CoreFactory {
  def createCoreFrom(config: RawRorConfig,
                     rorIndexNameConfiguration: RorConfigurationIndex,
                     httpClientFactory: HttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                     mocksProvider: MocksProvider): Task[Either[NonEmptyList[AclCreationError], CoreSettings]]
}

class RawRorConfigBasedCoreFactory(rorMode: RorMode)
                                  (implicit clock: Clock,
                                   uuidProvider: UuidProvider,
                                   envVarProvider: EnvVarsProvider)
  extends CoreFactory with Logging {

  override def createCoreFrom(config: RawRorConfig,
                              rorIndexNameConfiguration: RorConfigurationIndex,
                              httpClientFactory: HttpClientsFactory,
                              ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                              mocksProvider: MocksProvider): Task[Either[NonEmptyList[AclCreationError], CoreSettings]] = {
    config.configJson \\ Attributes.rorSectionName match {
      case Nil => createCoreFromRorSection(
        config.configJson,
        rorIndexNameConfiguration,
        httpClientFactory,
        ldapConnectionPoolProvider,
        mocksProvider
      )
      case rorSection :: Nil => createCoreFromRorSection(
        rorSection,
        rorIndexNameConfiguration,
        httpClientFactory,
        ldapConnectionPoolProvider,
        mocksProvider
      )
      case _ => Task.now(Left(NonEmptyList.one(GeneralReadonlyrestSettingsError(Message(s"Malformed settings")))))
    }
  }

  private def createCoreFromRorSection(rorSection: Json,
                                       rorIndexNameConfiguration: RorConfigurationIndex,
                                       httpClientFactory: HttpClientsFactory,
                                       ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                                       mocksProvider: MocksProvider) = {
    JsonConfigStaticVariableResolver.resolve(rorSection) match {
      case Right(resolvedRorSection) =>
        createFrom(resolvedRorSection, rorIndexNameConfiguration, httpClientFactory, ldapConnectionPoolProvider, mocksProvider).map {
          case Right(settings) =>
            Right(settings)
          case Left(failure) =>
            Left(NonEmptyList.one(failure.aclCreationError.getOrElse(GeneralReadonlyrestSettingsError(Message(s"Malformed settings")))))
        }
      case Left(errors) =>
        Task.now(Left(errors.map(e => GeneralReadonlyrestSettingsError(Message(e.msg)))))
    }
  }

  private def createFrom(settingsJson: Json,
                         rorConfigurationIndex: RorConfigurationIndex,
                         httpClientFactory: HttpClientsFactory,
                         ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                         mocksProvider: MocksProvider) = {
    val decoder = for {
      enabled <- AsyncDecoderCreator.from(coreEnabilityDecoder)
      core <-
      if (!enabled) {
        AsyncDecoderCreator
          .from(Decoder.const(CoreSettings(DisabledAccessControl, None)))
      } else {
        for {
          auditingTools <- AsyncDecoderCreator.from(AuditingSettingsDecoder.instance)
          globalSettings <- AsyncDecoderCreator.from(GlobalStaticSettingsDecoder.instance(
            rorMode,
            rorConfigurationIndex
          ))
          acl <- aclDecoder(httpClientFactory, ldapConnectionPoolProvider, globalSettings, mocksProvider)
        } yield CoreSettings(
          aclEngine = acl,
          auditingSettings = auditingTools
        )
      }
    } yield core

    decoder(HCursor.fromJson(settingsJson))
  }

  private def coreEnabilityDecoder: Decoder[Boolean] = {
    Decoder.instance { c =>
      for {
        enabled <- c.downField("enable").as[Option[Boolean]]
      } yield enabled.getOrElse(true)
    }
  }

  import RawRorConfigBasedCoreFactory._

  private def rulesNelDecoder(definitions: DefinitionsPack,
                              globalSettings: GlobalSettings,
                              mocksProvider: MocksProvider): Decoder[NonEmptyList[RuleWithVariableUsageDefinition[Rule]]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Validated[List[String], Decoder.Result[List[RuleWithVariableUsageDefinition[Rule]]]]](Validated.Valid(Right(List.empty)))

    val (_, result) = c.keys.toList.flatten // at the moment kibana_index must be defined before kibana_access
      .foldLeft(init) { case (collectedRuleResults, currentRuleName) =>
      for {
        last <- collectedRuleResults
        current <- decodeRuleInCursorContext(currentRuleName, definitions, globalSettings, mocksProvider).map {
          case RuleDecodingResult.Result(value) => Validated.Valid(value.map(_ :: Nil))
          case RuleDecodingResult.UnknownRule => Validated.Invalid(currentRuleName :: Nil)
          case RuleDecodingResult.Skipped => Validated.Valid(Right(List.empty))
        }
      } yield Monoid.combine(last, current)
    }
      .run(c)
      .value

    result match {
      case Validated.Valid(r) =>
        r.flatMap { a =>
          NonEmptyList.fromList(a) match {
            case Some(rules) =>
              Right(rules)
            case None =>
              Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"No rules defined in block"))))
          }
        }
      case Validated.Invalid(unknownRules) =>
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Unknown rules: ${unknownRules.mkString(",")}"))))
    }
  }

  private def decodeRuleInCursorContext(name: String,
                                        definitions: DefinitionsPack,
                                        globalSettings: GlobalSettings,
                                        mocksProvider: MocksProvider): State[ACursor, RuleDecodingResult] = {
    val caseMappingEquality: UserIdCaseMappingEquality = createUserMappingEquality(globalSettings)
    State(cursor => {
      if (!cursor.keys.toList.flatten.contains(name)) {
        (cursor, RuleDecodingResult.Skipped)
      } else {
        ruleDecoderBy(Rule.Name(name), definitions, globalSettings, mocksProvider, caseMappingEquality) match {
          case Some(decoder) =>
            decoder.tryDecode(cursor) match {
              case Right(RuleDecoder.Result(rule, unconsumedCursor)) =>
                (unconsumedCursor, RuleDecodingResult.Result(Right(rule)))
              case Left(failure) =>
                (cursor, RuleDecodingResult.Result(Left(failure)))
            }
          case None =>
            (cursor, RuleDecodingResult.UnknownRule)
        }
      }
    })
  }

  private def createUserMappingEquality(globalSettings: GlobalSettings) = {
    globalSettings.usernameCaseMapping match {
      case UsernameCaseMapping.CaseSensitive => UserIdEq.caseSensitive
      case UsernameCaseMapping.CaseInsensitive => UserIdEq.caseInsensitive
    }
  }

  private def blockDecoder(definitions: DefinitionsPack,
                           globalSettings: GlobalSettings,
                           mocksProvider: MocksProvider)
                          (implicit loggingContext: LoggingContext): Decoder[Block] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderHelpers.decodeStringLike.map(Block.Name.apply)
    implicit val policyDecoder: Decoder[Block.Policy] =
      Decoder
        .decodeString
        .toSyncDecoder
        .emapE[Block.Policy] {
        case "allow" => Right(Block.Policy.Allow)
        case "forbid" => Right(Block.Policy.Forbid)
        case unknown => Left(BlocksLevelCreationError(Message(s"Unknown block policy type: $unknown")))
      }
        .decoder
    implicit val verbosityDecoder: Decoder[Verbosity] =
      Decoder
        .decodeString
        .toSyncDecoder
        .emapE[Verbosity] {
        case "info" => Right(Verbosity.Info)
        case "error" => Right(Verbosity.Error)
        case unknown => Left(BlocksLevelCreationError(Message(s"Unknown verbosity value: $unknown")))
      }
        .decoder
    Decoder
      .instance { c =>
        val result = for {
          name <- c.downField(Attributes.Block.name).as[Block.Name]
          policy <- c.downField(Attributes.Block.policy).as[Option[Block.Policy]]
          verbosity <- c.downField(Attributes.Block.verbosity).as[Option[Block.Verbosity]]
          rules <- rulesNelDecoder(definitions, globalSettings, mocksProvider)
            .toSyncDecoder
            .decoder
            .tryDecode(c.withFocus(
              _.mapObject(_
                .remove(Attributes.Block.name)
                .remove(Attributes.Block.policy)
                .remove(Attributes.Block.verbosity))
            ))
          block <- Block.createFrom(name, policy, verbosity, rules).left.map(DecodingFailureOps.fromError(_))
        } yield block
        result.left.map(_.overrideDefaultErrorWith(BlocksLevelCreationError(MalformedValue(c.value))))
      }
  }

  private val obfuscatedHeadersAsyncDecoder: Decoder[Set[Header.Name]] = {
    import tech.beshu.ror.accesscontrol.factory.decoders.common.headerName
    Decoder.instance(_.downField("obfuscated_headers").as[Option[Set[Header.Name]]])
      .map(_.getOrElse(Set(Header.Name.authorization)))
  }

  private def aclDecoder(httpClientFactory: HttpClientsFactory,
                         ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                         globalSettings: GlobalSettings,
                         mocksProvider: MocksProvider): AsyncDecoder[AccessControl] = {
    val caseMappingEquality: UserIdCaseMappingEquality = createUserMappingEquality(globalSettings)
    AsyncDecoderCreator.instance[AccessControl] { c =>
      val decoder = for {
        authProxies <- AsyncDecoderCreator.from(ProxyAuthDefinitionsDecoder.instance)
        authenticationServices <- AsyncDecoderCreator.from(ExternalAuthenticationServicesDecoder.instance(httpClientFactory))
        authorizationServices <- AsyncDecoderCreator.from(ExternalAuthorizationServicesDecoder.instance(httpClientFactory))
        jwtDefs <- AsyncDecoderCreator.from(JwtDefinitionsDecoder.instance(httpClientFactory))
        ldapServices <- LdapServicesDecoder.ldapServicesDefinitionsDecoder(ldapConnectionPoolProvider)
        rorKbnDefs <- AsyncDecoderCreator.from(RorKbnDefinitionsDecoder.instance())
        impersonationDefinitionsDecoderCreator = new ImpersonationDefinitionsDecoderCreator(
          caseMappingEquality, authenticationServices, authProxies, jwtDefs, ldapServices, rorKbnDefs, mocksProvider
        )
        impersonationDefs <- AsyncDecoderCreator.from(impersonationDefinitionsDecoderCreator.create)
        userDefs <- AsyncDecoderCreator.from(UsersDefinitionsDecoder.instance(
          authenticationServices,
          authorizationServices,
          authProxies,
          jwtDefs,
          rorKbnDefs,
          ldapServices,
          Some(impersonationDefs),
          mocksProvider,
          caseMappingEquality
        ))
        obfuscatedHeaders <- AsyncDecoderCreator.from(obfuscatedHeadersAsyncDecoder)
        blocks <- {
          implicit val loggingContext: LoggingContext = LoggingContext(obfuscatedHeaders)
          implicit val blockAsyncDecoder: AsyncDecoder[Block] = AsyncDecoderCreator.from {
            blockDecoder(
              DefinitionsPack(
                proxies = authProxies,
                users = userDefs,
                authenticationServices = authenticationServices,
                authorizationServices = authorizationServices,
                jwts = jwtDefs,
                rorKbns = rorKbnDefs,
                ldaps = ldapServices,
                impersonators = impersonationDefs
              ),
              globalSettings,
              mocksProvider
            )
          }
          DecoderHelpers
            .decodeFieldList[Block, Task](Attributes.acl, RulesLevelCreationError.apply)
            .emapE {
              case NoField => Left(BlocksLevelCreationError(Message(s"No ${Attributes.acl} section found")))
              case FieldListValue(blocks) =>
                NonEmptyList.fromList(blocks) match {
                  case None =>
                    Left(BlocksLevelCreationError(Message(s"${Attributes.acl} defined, but no block found")))
                  case Some(neBlocks) =>
                    neBlocks.map(_.name).toList.findDuplicates match {
                      case Nil => Right(neBlocks)
                      case duplicates => Left(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: ${duplicates.map(_.show).mkString(",")}")))
                    }
                }
            }
        }
      } yield {
        implicit val loggingContext: LoggingContext = LoggingContext(obfuscatedHeaders)
        val upgradedBlocks = CrossBlockContextBlocksUpgrade.upgrade(blocks)
        upgradedBlocks.toList.foreach { block => logger.info("ADDING BLOCK:\t" + block.show) }
        new AccessControlList(
          upgradedBlocks,
          new AccessControlListStaticContext(
            blocks,
            globalSettings,
            obfuscatedHeaders
          )
        ): AccessControl
      }
      decoder.apply(c)
    }
  }
}

object RawRorConfigBasedCoreFactory {

  sealed trait AclCreationError {
    def reason: Reason
  }

  object AclCreationError {

    final case class GeneralReadonlyrestSettingsError(reason: Reason) extends AclCreationError
    final case class DefinitionsLevelCreationError(reason: Reason) extends AclCreationError
    final case class BlocksLevelCreationError(reason: Reason) extends AclCreationError
    final case class RulesLevelCreationError(reason: Reason) extends AclCreationError
    final case class ValueLevelCreationError(reason: Reason) extends AclCreationError
    final case class AuditingSettingsCreationError(reason: Reason) extends AclCreationError

    sealed trait Reason
    object Reason {

      final case class Message(value: String) extends Reason
      final case class MalformedValue private(value: String) extends Reason
      object MalformedValue {
        def apply(json: Json): MalformedValue = from(json)

        def from(json: Json): MalformedValue = MalformedValue {
          YamlOps.jsonToYamlString(json)
        }
      }

    }

  }

  private sealed trait RuleDecodingResult
  private object RuleDecodingResult {
    final case class Result(value: Decoder.Result[RuleWithVariableUsageDefinition[Rule]]) extends RuleDecodingResult
    case object UnknownRule extends RuleDecodingResult
    case object Skipped extends RuleDecodingResult
  }

  private object Attributes {
    val rorSectionName = "readonlyrest"
    val acl = "access_control_rules"

    object Block {
      val name = "name"
      val policy = "type"
      val verbosity = "verbosity"
    }

  }

}
