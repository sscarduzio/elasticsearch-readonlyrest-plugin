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
import cats.kernel.Monoid
import io.circe.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.audit.LoggingContext
import tech.beshu.ror.accesscontrol.blocks.Block.{RuleDefinition, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, UserDef}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.{LocalUsersSupport, localUsersMonoid}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.blocks.{Block, ImpersonationWarning}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.*
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.*
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.{AuditingSettingsDecoder, GlobalStaticSettingsDecoder}
import tech.beshu.ror.accesscontrol.utils.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.configuration.RorConfig.ImpersonationWarningsReader
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig, RorConfig}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.yaml.YamlOps

final case class Core(accessControl: AccessControlList,
                      rorConfig: RorConfig)

trait CoreFactory {
  def createCoreFrom(config: RawRorConfig,
                     rorIndexNameConfiguration: RorConfigurationIndex,
                     httpClientFactory: HttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                     mocksProvider: MocksProvider): Task[Either[NonEmptyList[CoreCreationError], Core]]
}

class RawRorConfigBasedCoreFactory()
                                  (implicit environmentConfig: EnvironmentConfig)
  extends CoreFactory with Logging {

  override def createCoreFrom(config: RawRorConfig,
                              rorIndexNameConfiguration: RorConfigurationIndex,
                              httpClientFactory: HttpClientsFactory,
                              ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                              mocksProvider: MocksProvider): Task[Either[NonEmptyList[CoreCreationError], Core]] = {
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
    val jsonConfigResolver = new JsonConfigStaticVariableResolver(
      environmentConfig.envVarsProvider,
      TransformationCompiler.withoutAliases(environmentConfig.variablesFunctions),
    )
    jsonConfigResolver.resolve(rorSection) match {
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
          .from(Decoder.const(Core(DisabledAccessControlList, RorConfig.disabled)))
      } else {
        for {
          globalSettings <- AsyncDecoderCreator.from(GlobalStaticSettingsDecoder.instance(rorConfigurationIndex))
          core <- coreDecoder(httpClientFactory, ldapConnectionPoolProvider, globalSettings, mocksProvider)
        } yield core
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

  import RawRorConfigBasedCoreFactory.*

  private def rulesNelDecoder(definitions: DefinitionsPack,
                              globalSettings: GlobalSettings,
                              mocksProvider: MocksProvider): Decoder[NonEmptyList[RuleDefinition[Rule]]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Validated[List[String], Decoder.Result[List[RuleDefinition[Rule]]]]](Validated.Valid(Right(List.empty)))

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
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Unknown rules: ${unknownRules.show}"))))
    }
  }

  private def decodeRuleInCursorContext(name: String,
                                        definitions: DefinitionsPack,
                                        globalSettings: GlobalSettings,
                                        mocksProvider: MocksProvider): State[ACursor, RuleDecodingResult] = {
    State(cursor => {
      if (!cursor.keys.toList.flatten.contains(name)) {
        (cursor, RuleDecodingResult.Skipped)
      } else {
        ruleDecoderBy(Rule.Name(name), definitions, globalSettings, mocksProvider) match {
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

  private def blockDecoder(definitions: DefinitionsPack,
                           globalSettings: GlobalSettings,
                           mocksProvider: MocksProvider)
                          (implicit loggingContext: LoggingContext): Decoder[BlockDecodingResult] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderHelpers.decodeStringLike.map(Block.Name.apply)
    implicit val policyDecoder: Decoder[Block.Policy] = this.policyDecoder
    implicit val verbosityDecoder: Decoder[Verbosity] =
      Decoder
        .decodeString
        .toSyncDecoder
        .emapE[Verbosity] {
        case "info" => Right(Verbosity.Info)
        case "error" => Right(Verbosity.Error)
        case unknown => Left(BlocksLevelCreationError(Message(s"Unknown verbosity value: ${unknown.show}. Supported types: 'info'(default), 'error'.")))
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
        } yield BlockDecodingResult(
          block = block,
          localUsers = rules.map(localUsersForRule).combineAll,
          impersonationWarnings = new BlockImpersonationWarningsReader(block.name, rules)
        )
        result.left.map(_.overrideDefaultErrorWith(BlocksLevelCreationError(MalformedValue(c.value))))
      }
  }

  private val obfuscatedHeadersAsyncDecoder: Decoder[Set[Header.Name]] = {
    import tech.beshu.ror.accesscontrol.factory.decoders.common.headerName
    Decoder.instance(_.downField("obfuscated_headers").as[Option[Set[Header.Name]]])
      .map(_.getOrElse(Set(Header.Name.authorization)))
  }

  private val policyDecoder: Decoder[Block.Policy] = {
    def unknownTypeError(unknownType: String) =
      BlocksLevelCreationError(Message(
        s"Unknown block policy type: ${unknownType.show}. Supported types: 'allow'(default), 'forbid'."
      ))

    val simplePolicyDecoder = {
      Decoder
        .decodeString
        .toSyncDecoder
        .emapE[Block.Policy] {
          case "allow" => Right(Block.Policy.Allow)
          case "forbid" => Right(Block.Policy.Forbid())
          case unknown => Left(unknownTypeError(unknown))
        }
        .decoder
    }

    val extendedPolicyDecoder = {
      Decoder
        .instance { c =>
          for {
            policyType <- c.downFieldAs[String]("policy")
            policy <- policyType match {
              case "allow" => Right(Block.Policy.Allow)
              case "forbid" => c.downFieldAs[Option[String]]("response_message").map(Block.Policy.Forbid.apply)
              case unknown => Left(DecodingFailureOps.fromError(unknownTypeError(unknown)))
            }
          } yield policy
        }
        .toSyncDecoder
        .decoder
    }

    Decoder.instance { c =>
      c.focus match {
        case Some(f) if f.isString => simplePolicyDecoder(c)
        case Some(f) if f.isObject => extendedPolicyDecoder(c)
        case Some(_) | None => Left(DecodingFailure("Malformed block policy type", c.history))
      }
    }
  }

  private def localUsersForRule[R <: Rule](rule: RuleDefinition[R]) = {
    rule.localUsersSupport match {
      case users: LocalUsersSupport.AvailableLocalUsers[R] => users.definedLocalUsers(rule.rule)
      case LocalUsersSupport.NotAvailableLocalUsers() => LocalUsers.empty
    }
  }

  private def coreDecoder(httpClientFactory: HttpClientsFactory,
                          ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                          globalSettings: GlobalSettings,
                          mocksProvider: MocksProvider): AsyncDecoder[Core] = {
    AsyncDecoderCreator.instance[Core] { c =>
      val decoder = for {
        dynamicVariableTransformationAliases <-
          AsyncDecoderCreator.from(VariableTransformationAliasesDefinitionsDecoder.create(environmentConfig.variablesFunctions))
        variableCreator = new RuntimeResolvableVariableCreator(
          TransformationCompiler.withAliases(
            environmentConfig.variablesFunctions,
            dynamicVariableTransformationAliases.items.map(_.alias)
          )
        )
        auditingTools <- AsyncDecoderCreator.from(AuditingSettingsDecoder.instance)
        authProxies <- AsyncDecoderCreator.from(ProxyAuthDefinitionsDecoder.instance)
        authenticationServices <- AsyncDecoderCreator.from(ExternalAuthenticationServicesDecoder.instance(httpClientFactory))
        authorizationServices <- AsyncDecoderCreator.from(ExternalAuthorizationServicesDecoder.instance(httpClientFactory))
        jwtDefs <- AsyncDecoderCreator.from(JwtDefinitionsDecoder.instance(httpClientFactory, variableCreator))
        ldapServices <- LdapServicesDecoder.ldapServicesDefinitionsDecoder(using ldapConnectionPoolProvider, environmentConfig.clock)
        rorKbnDefs <- AsyncDecoderCreator.from(RorKbnDefinitionsDecoder.instance(variableCreator))
        impersonationDefinitionsDecoderCreator = new ImpersonationDefinitionsDecoderCreator(
          globalSettings, authenticationServices, authProxies, ldapServices, mocksProvider
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
          globalSettings
        ))
        obfuscatedHeaders <- AsyncDecoderCreator.from(obfuscatedHeadersAsyncDecoder)
        blocksNel <- {
          implicit val loggingContext: LoggingContext = LoggingContext(obfuscatedHeaders)
          implicit val blockAsyncDecoder: AsyncDecoder[BlockDecodingResult] = AsyncDecoderCreator.from {
            blockDecoder(
              DefinitionsPack(
                proxies = authProxies,
                users = userDefs,
                authenticationServices = authenticationServices,
                authorizationServices = authorizationServices,
                jwts = jwtDefs,
                rorKbns = rorKbnDefs,
                ldaps = ldapServices,
                impersonators = impersonationDefs,
                variableTransformationAliases = dynamicVariableTransformationAliases,
              ),
              globalSettings,
              mocksProvider,
            )
          }
          DecoderHelpers
            .decodeFieldList[BlockDecodingResult, Task](Attributes.acl, RulesLevelCreationError.apply)
            .emapE {
              case NoField => Left(BlocksLevelCreationError(Message(s"No ${Attributes.acl.show} section found")))
              case FieldListValue(blocks) =>
                NonEmptyList.fromList(blocks) match {
                  case None =>
                    Left(BlocksLevelCreationError(Message(s"${Attributes.acl.show} defined, but no block found")))
                  case Some(neBlocks) =>
                    neBlocks.map(_.block.name).toList.findDuplicates match {
                      case Nil => Right(neBlocks)
                      case duplicates => Left(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: ${duplicates.show}")))
                    }
                }
            }
        }
      } yield {
        val blocks = blocksNel.map(_.block)
        blocks.toList.foreach { block => logger.info(s"ADDING BLOCK:\t ${block.show}") }
        val localUsers: LocalUsers = {
          val fromUserDefs = localUsersFromUserDefs(userDefs)
          val fromImpersonatorDefs = localUsersFromImpersonatorDefs(impersonationDefs)
          val fromBlocks = blocksNel.map(_.localUsers).toList
          (fromBlocks :+ fromUserDefs :+ fromImpersonatorDefs).combineAll
        }

        val rorConfig = RorConfig(
          services = RorConfig.Services(
            authenticationServices = authenticationServices.items.map(_.id),
            authorizationServices = authorizationServices.items.map(_.id),
            ldaps = ldapServices.items.map(_.id)
          ),
          localUsers = localUsers,
          impersonationWarningsReader = new ImpersonationWarningsCombinedReader(blocksNel.map(_.impersonationWarnings).toList: _*),
          auditingSettings = auditingTools,
        )
        val accessControl = new EnabledAccessControlList(
          blocks,
          new AccessControlListStaticContext(
            blocks,
            globalSettings,
            obfuscatedHeaders
          )
        ): AccessControlList
        Core(accessControl, rorConfig)
      }
      decoder.apply(c)
    }
  }

  private def localUsersFromUserDefs(definitions: Definitions[UserDef]) = {
    definitions.items
      .flatMap { definition =>
        List(
          localUsersFromUsernamePatterns(definition.usernames, unknownUsersForWildcardPattern = true),
          localUsersFromMode(definition.mode)
        )
      }
      .combineAll
  }

  private def localUsersFromImpersonatorDefs(definitions: Definitions[ImpersonatorDef]) = {
    definitions.items
      .map(_.impersonatedUsers.usernames)
      .map(localUsersFromUsernamePatterns(_, unknownUsersForWildcardPattern = false))
      .combineAll
  }

  private def localUsersFromUsernamePatterns(userIdPatterns: UserIdPatterns,
                                             unknownUsersForWildcardPattern: Boolean): LocalUsers = {
    userIdPatterns
      .patterns
      .map { userIdPattern =>
        if (userIdPattern.containsWildcard) {
          LocalUsers(users = Set.empty, unknownUsers = unknownUsersForWildcardPattern)
        } else {
          LocalUsers(users = Set(userIdPattern.value), unknownUsers = false)
        }
      }
      .toList
      .combineAll
  }

  private def localUsersFromMode(mode: UserDef.Mode): LocalUsers = {
    def localUsersFor(support: EligibleUsersSupport) = support match {
      case EligibleUsersSupport.Available(users) => LocalUsers(users, unknownUsers = false)
      case EligibleUsersSupport.NotAvailable => LocalUsers.empty
    }

    mode match {
      case Mode.WithoutGroupsMapping(rule, _) => localUsersFor(rule.eligibleUsers)
      case Mode.WithGroupsMapping(Auth.SeparateRules(rule, _), _) => localUsersFor(rule.eligibleUsers)
      case Mode.WithGroupsMapping(Auth.SingleRule(rule), _) => localUsersFor(rule.eligibleUsers)
    }
  }
}

object RawRorConfigBasedCoreFactory {

  sealed trait CoreCreationError {
    def reason: Reason
  }

  object CoreCreationError {

    final case class GeneralReadonlyrestSettingsError(reason: Reason) extends CoreCreationError
    final case class DefinitionsLevelCreationError(reason: Reason) extends CoreCreationError
    final case class BlocksLevelCreationError(reason: Reason) extends CoreCreationError
    final case class RulesLevelCreationError(reason: Reason) extends CoreCreationError
    final case class ValueLevelCreationError(reason: Reason) extends CoreCreationError
    final case class AuditingSettingsCreationError(reason: Reason) extends CoreCreationError

    sealed trait Reason
    object Reason {

      final case class Message(value: String) extends Reason
      final case class MalformedValue private(value: String) extends Reason
      object MalformedValue {
        def fromString(str: String): MalformedValue = MalformedValue(str)
        
        def apply(json: Json): MalformedValue = from(json)

        def from(json: Json): MalformedValue = MalformedValue {
          YamlOps.jsonToYamlString(json)
        }
      }

    }

  }

  private class ImpersonationWarningsCombinedReader(readers: ImpersonationWarningsReader*)
    extends ImpersonationWarningsReader {

    override def read()
                     (implicit requestId: RequestId): List[ImpersonationWarning] = readers.flatMap(_.read()).toList
  }

  private class BlockImpersonationWarningsReader[R <: Rule](blockName: Block.Name,
                                                            blockRules: NonEmptyList[RuleDefinition[R]])
    extends ImpersonationWarningsReader {

    override def read()
                     (implicit request: RequestId): List[ImpersonationWarning] = {
      blockRules
        .toList
        .flatMap(impersonationWarningForRule(_))
    }

    private def impersonationWarningForRule(rule: RuleDefinition[R])
                                           (implicit requestId: RequestId): List[ImpersonationWarning] = {
      rule.impersonationWarnings match {
        case extractor: ImpersonationWarningSupport.ImpersonationWarningExtractor[_] =>
          extractor.warningFor(rule.rule, blockName).toList
        case ImpersonationWarningSupport.NotSupported() =>
          List.empty
      }
    }
  }

  private case class BlockDecodingResult(block: Block,
                                         localUsers: LocalUsers,
                                         impersonationWarnings: ImpersonationWarningsReader)

  private sealed trait RuleDecodingResult
  private object RuleDecodingResult {
    final case class Result(value: Decoder.Result[RuleDefinition[Rule]]) extends RuleDecodingResult
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
