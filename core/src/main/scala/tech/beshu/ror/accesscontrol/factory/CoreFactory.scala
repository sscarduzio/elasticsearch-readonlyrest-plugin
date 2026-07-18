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
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.{AuditOutputsConfig, AuditingConfig}
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, EsAuditCapabilities, LoggingContext}
import tech.beshu.ror.accesscontrol.blocks.Block.Audit.Enabled.EnabledAuditSinks
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.blocks.{Block, ImpersonationWarning}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.CoreFactory.CoreCreationResult
import tech.beshu.ror.accesscontrol.factory.CoreFactory.CoreCreationResult.*
import tech.beshu.ror.accesscontrol.factory.CoreFactory.CoreCreationResult.AuditSetup.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{
  MalformedValue,
  Message
}
import tech.beshu.ror.accesscontrol.factory.RorDependencies.ImpersonationWarningsReader
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.*
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.{AuditingSettingsDecoder, GlobalStaticSettingsDecoder}
import tech.beshu.ror.accesscontrol.utils.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureUtils.decodingFailureFrom
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.RawRorSettings
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.yaml.YamlOps

final case class Core(
    accessControl: AccessControlList,
    dependencies: RorDependencies,
    auditingConfig: AuditingTool.AuditingConfig[AuditSink.Config]
)

trait CoreFactory {

  def createCoreFrom(
      rorSettings: RawRorSettings,
      rorSettingsIndex: RorSettingsIndex,
      httpClientFactory: HttpClientsFactory,
      ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
      mocksProvider: MocksProvider,
      auditCapabilities: EsAuditCapabilities
  ): Task[Either[NonEmptyList[CoreCreationError], CoreCreationResult]]

}

object CoreFactory {

  final class CoreCreationResult(val core: Core, val auditSetup: Option[CoreCreationResult.AuditSetup])

  object CoreCreationResult {
    sealed trait AuditSetup

    object AuditSetup {

      final class Index(
          val capability: EsAuditCapabilities.Index,
          val settings: AuditSettings.Legacy
      ) extends AuditSetup

      final class IndexWithDataStream(
          val capability: EsAuditCapabilities.IndexWithDataStream,
          val settings: AuditSettings.Standard
      ) extends AuditSetup

    }

    extension (audit: AuditSetup) {

      def settings: AuditSettings[AuditSink.Config] = audit match {
        case index: AuditSetup.Index                => index.settings
        case stream: AuditSetup.IndexWithDataStream => stream.settings
      }

    }

  }

}

class RawRorSettingsBasedCoreFactory(esEnv: EsEnv)(
    implicit systemContext: SystemContext
) extends CoreFactory
    with RequestIdAwareLogging {

  override def createCoreFrom(
      rorSettings: RawRorSettings,
      rorSettingsIndex: RorSettingsIndex,
      httpClientFactory: HttpClientsFactory,
      ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
      mocksProvider: MocksProvider,
      auditCapabilities: EsAuditCapabilities
  ): Task[Either[NonEmptyList[CoreCreationError], CoreCreationResult]] = {
    rorSettings.settingsJson \\ Attributes.rorSectionName match {
      case Nil =>
        createCoreFromRorSection(
          rorSettings.settingsJson,
          rorSettingsIndex,
          httpClientFactory,
          ldapConnectionPoolProvider,
          mocksProvider,
          auditCapabilities,
        )
      case rorSection :: Nil =>
        createCoreFromRorSection(
          rorSection,
          rorSettingsIndex,
          httpClientFactory,
          ldapConnectionPoolProvider,
          mocksProvider,
          auditCapabilities,
        )
      case _ => Task.now(Left(NonEmptyList.one(GeneralReadonlyrestSettingsError(Message(s"Malformed settings")))))
    }
  }

  private def createCoreFromRorSection(
      rorSection: Json,
      rorSettingsIndex: RorSettingsIndex,
      httpClientFactory: HttpClientsFactory,
      ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
      mocksProvider: MocksProvider,
      auditCapabilities: EsAuditCapabilities
  ) = {
    val resolver = new JsonStaticVariablesResolver(
      systemContext.envVarsProvider,
      TransformationCompiler.withoutAliases(systemContext.variablesFunctions),
    )
    resolver.resolve(rorSection) match {
      case Right(resolvedRorSection) =>
        createFrom(
          resolvedRorSection,
          rorSettingsIndex,
          httpClientFactory,
          ldapConnectionPoolProvider,
          mocksProvider,
          auditCapabilities
        )
          .map {
            case Right(settings) =>
              Right(settings)
            case Left(failure) =>
              Left(
                NonEmptyList.one(
                  failure.aclCreationError.getOrElse(GeneralReadonlyrestSettingsError(Message(s"Malformed settings")))
                )
              )
          }
      case Left(errors) =>
        Task.now(Left(errors.map(e => GeneralReadonlyrestSettingsError(Message(e.msg)))))
    }
  }

  private def createFrom(
      settingsJson: Json,
      settingsIndex: RorSettingsIndex,
      httpClientFactory: HttpClientsFactory,
      ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
      mocksProvider: MocksProvider,
      auditCapabilities: EsAuditCapabilities
  ) = {
    val decoder = for {
      enabled <- AsyncDecoderCreator.from(coreEnabilityDecoder)
      result <-
        if (!enabled) {
          AsyncDecoderCreator.from(
            Decoder.const(
              new CoreCreationResult(
                Core(
                  accessControl = DisabledAccessControlList,
                  dependencies = RorDependencies.noOp,
                  auditingConfig = AuditingTool.AuditingConfig(None, defaultAclLog = true, esEnv.esNodeSettings),
                ),
                None
              )
            )
          )
        } else {
          for {
            globalSettings <- AsyncDecoderCreator.from(GlobalStaticSettingsDecoder.instance(settingsIndex))
            result <- coreDecoder(
              httpClientFactory,
              ldapConnectionPoolProvider,
              globalSettings,
              mocksProvider,
              auditCapabilities
            )
          } yield result
        }
    } yield result

    decoder(HCursor.fromJson(settingsJson))
  }

  private def coreEnabilityDecoder: Decoder[Boolean] = {
    Decoder.instance { c =>
      for {
        enabled <- c.downField("enable").as[Option[Boolean]]
      } yield enabled.getOrElse(true)
    }
  }

  import RawRorSettingsBasedCoreFactory.*

  private def rulesNelDecoder(
      definitions: DefinitionsPack,
      globalSettings: GlobalSettings,
      mocksProvider: MocksProvider,
      esEnv: EsEnv
  ): Decoder[NonEmptyList[RuleDefinition[Rule]]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Validated[List[String], Decoder.Result[List[RuleDefinition[Rule]]]]](
      Validated.Valid(Right(List.empty))
    )

    val (_, result) = c.keys.toList.flatten // at the moment kibana_index must be defined before kibana_access
      .foldLeft(init) { case (collectedRuleResults, currentRuleName) =>
        for {
          last <- collectedRuleResults
          current <- decodeRuleInCursorContext(currentRuleName, definitions, globalSettings, mocksProvider, esEnv).map {
            case RuleDecodingResult.Result(value) => Validated.Valid(value.map(_ :: Nil))
            case RuleDecodingResult.UnknownRule   => Validated.Invalid(currentRuleName :: Nil)
            case RuleDecodingResult.Skipped       => Validated.Valid(Right(List.empty))
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
              Left(decodingFailureFrom(RulesLevelCreationError(Message(s"No rules defined in block"))))
          }
        }
      case Validated.Invalid(unknownRules) =>
        Left(decodingFailureFrom(RulesLevelCreationError(Message(s"Unknown rules: ${unknownRules.show}"))))
    }
  }

  private def decodeRuleInCursorContext(
      name: String,
      definitions: DefinitionsPack,
      globalSettings: GlobalSettings,
      mocksProvider: MocksProvider,
      esEnv: EsEnv
  ): State[ACursor, RuleDecodingResult] = {
    State(cursor => {
      if (!cursor.keys.toList.flatten.contains(name)) {
        (cursor, RuleDecodingResult.Skipped)
      } else {
        ruleDecoderBy(Rule.Name(name), definitions, globalSettings, mocksProvider, esEnv) match {
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

  private def blockDecoder(
      definitions: DefinitionsPack,
      globalSettings: GlobalSettings,
      mocksProvider: MocksProvider,
      esEnv: EsEnv
  )(
      implicit loggingContext: LoggingContext
  ): Decoder[BlockDecodingResult] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderHelpers.decodeStringLike.map(Block.Name.apply)
    implicit val policyDecoder: Decoder[Block.Policy] = this.policyDecoder
    implicit val sinkNameDecoder: Decoder[SinkName] =
      Decoder.decodeString.map(SinkName.apply)
    implicit val blockAuditDecoder: Decoder[Block.Audit] = this.blockAuditDecoder

    Decoder
      .instance { c =>
        val result = for {
          name <- c.downField(Attributes.Block.name).as[Block.Name]
          policy <- c.downField(Attributes.Block.policy).as[Option[Block.Policy]]
          legacyVerbosityAudit <- c.as[Option[Block.Audit]](legacyVerbosityAuditDecoder)
          auditFromConfig <- c.downField(Attributes.Block.audit).as[Option[Block.Audit]]
          audit <- resolveBlockAudit(auditFromConfig, legacyVerbosityAudit)
          rules <- rulesNelDecoder(definitions, globalSettings, mocksProvider, esEnv).toSyncDecoder.decoder
            .tryDecode(
              c.withFocus(
                _.mapObject(
                  _.remove(Attributes.Block.name)
                    .remove(Attributes.Block.policy)
                    .remove(Attributes.Block.verbosity)
                    .remove(Attributes.Block.audit)
                )
              )
            )
          block <- Block.createFrom(name, policy, audit, rules).left.map(decodingFailureFrom(_))
        } yield BlockDecodingResult(
          block = block,
          localUsers = block.rules.map(LocalUsers.from).combineAll,
          impersonationWarnings = new BlockImpersonationWarningsReader(block.name, rules)
        )
        result.left.map(_.overrideDefaultErrorWith(BlocksLevelCreationError(MalformedValue(c.value))))
      }
  }

  private val legacyVerbosityAuditDecoder: Decoder[Option[Block.Audit]] = Decoder.instance { c =>
    c.downField(Attributes.Block.verbosity).as[Option[String]].flatMap {
      case None          => Right(None)
      case Some("info")  => Right(Some(Block.Audit.Enabled(logAllowedEvents = true)))
      case Some("error") => Right(Some(Block.Audit.Enabled(logAllowedEvents = false)))
      case Some(unknown) =>
        Left(
          decodingFailureFrom(
            BlocksLevelCreationError(
              Message(
                s"Unknown verbosity value: ${unknown.show}. Supported types: 'info'(default), 'error'."
              )
            )
          )
        )
    }
  }

  private def blockAuditDecoder(
      implicit sinkNameDecoder: Decoder[SinkName]
  ): Decoder[Block.Audit] =
    Decoder.instance { c =>
      for {
        enabledOpt <- c.downField("enabled").as[Option[Boolean]]
        enabled = enabledOpt.getOrElse(true)
        logAllowedEventsOpt <- c.downField("log_allowed_events").as[Option[Boolean]]
        logAllowedEvents = logAllowedEventsOpt.getOrElse(true)
        enabledAuditSinks <- enabledAuditSinksDecoder(c)
      } yield {
        if (enabled) {
          Block.Audit.Enabled(logAllowedEvents, enabledAuditSinks)
        } else {
          Block.Audit.Disabled
        }
      }
    }

  private def enabledAuditSinksDecoder(
      c: HCursor
  )(
      implicit sinkNameDecoder: Decoder[SinkName]
  ): Decoder.Result[EnabledAuditSinks] =
    for {
      enabledSinksRaw <- c.downField("enabled_audit_sinks").as[Option[List[SinkName]]]
      disabledSinksRaw <- c.downField("disabled_audit_sinks").as[Option[List[SinkName]]]
      enabledAuditSinks <- (enabledSinksRaw, disabledSinksRaw) match {
        case (Some(_), Some(_)) =>
          Left(
            decodingFailureFrom(
              BlocksLevelCreationError(
                Message(
                  "Cannot define both 'enabled_audit_sinks' and 'disabled_audit_sinks' in the same block audit config"
                )
              )
            )
          )
        case (Some(enabledSinks), None) =>
          Right(EnabledAuditSinks.Selected(enabledSinks.toSet))
        case (None, Some(disabledSinks)) =>
          Right(EnabledAuditSinks.AllExcept(disabledSinks.toSet))
        case (None, None) =>
          Right(EnabledAuditSinks.All)
      }
    } yield enabledAuditSinks

  private def resolveBlockAudit(
      auditFromConfig: Option[Block.Audit],
      legacyVerbosityAudit: Option[Block.Audit]
  ): Decoder.Result[Option[Block.Audit]] =
    (auditFromConfig, legacyVerbosityAudit) match {
      case (Some(_), Some(_)) =>
        Left(
          decodingFailureFrom(
            BlocksLevelCreationError(
              Message(
                s"Cannot use both '${Attributes.Block.verbosity}' and '${Attributes.Block.audit}' in the same block. " +
                  s"Please configure audit settings using '${Attributes.Block.audit}' only."
              )
            )
          )
        )
      case (auditOpt, legacyOpt) =>
        Right(auditOpt.orElse(legacyOpt))
    }

  private def auditSinkNamesDecoder(
      blocksNel: NonEmptyList[BlockDecodingResult],
      auditingConfig: AuditingTool.AuditingConfig
  ): AsyncDecoder[Unit] =
    AsyncDecoderCreator.instance[Unit] { _ =>
      Task.now {
        val configuredSinkNames: scala.collection.Set[SinkName] = auditingConfig.outputsConfig match {
          case Some(AuditOutputsConfig.WithOutputs(sinks)) =>
            sinks.toList.collect { case AuditSink.Enabled(name, _) => name }.toSet
          case _ => scala.collection.Set.empty
        }
        val globalSinkNames: scala.collection.Set[SinkName] =
          if (auditingConfig.defaultAclLog) configuredSinkNames ++ Set(SinkName.defaultAclLog)
          else configuredSinkNames
        val errors = blocksNel.toList.map(_.block).flatMap { block =>
          block.audit match {
            case Block.Audit.Enabled(_, EnabledAuditSinks.Selected(enabledSinks), _) =>
              if (enabledSinks.isEmpty)
                List(
                  s"Block '${block.name.value}': 'enabled_audit_sinks' cannot be empty; to disable all audit for this block use 'audit: {enabled: false}'"
                )
              else {
                val unknown = enabledSinks -- globalSinkNames
                if (unknown.nonEmpty)
                  List(
                    s"Block '${block.name.value}': 'enabled_audit_sinks' references unknown sink names [${unknown.map(_.value).mkString(", ")}]"
                  )
                else Nil
              }
            case Block.Audit.Enabled(_, EnabledAuditSinks.AllExcept(disabledSinks), _) =>
              if (disabledSinks.isEmpty)
                List(s"Block '${block.name.value}': 'disabled_audit_sinks' cannot be empty")
              else {
                val unknown = disabledSinks -- globalSinkNames
                if (unknown.nonEmpty)
                  List(
                    s"Block '${block.name.value}': 'disabled_audit_sinks' references unknown sink names [${unknown.map(_.value).mkString(", ")}]"
                  )
                else Nil
              }
            case _ => Nil
          }
        }
        if (errors.isEmpty) Right(())
        else Left(decodingFailureFrom(BlocksLevelCreationError(Message(errors.mkString("; ")))))
      }
    }

  private val obfuscatedHeadersAsyncDecoder: Decoder[Set[Header.Name]] = {
    import tech.beshu.ror.accesscontrol.factory.decoders.common.headerName
    Decoder
      .instance(_.downField("obfuscated_headers").as[Option[Set[Header.Name]]])
      .map(_.getOrElse(Set(Header.Name.authorization)))
  }

  private val policyDecoder: Decoder[Block.Policy] = {
    def unknownTypeError(unknownType: String) =
      BlocksLevelCreationError(
        Message(
          s"Unknown block policy type: ${unknownType.show}. Supported types: 'allow'(default), 'forbid'."
        )
      )

    val simplePolicyDecoder = {
      Decoder.decodeString.toSyncDecoder
        .emapE[Block.Policy] {
          case "allow"  => Right(Block.Policy.Allow)
          case "forbid" => Right(Block.Policy.Forbid())
          case unknown  => Left(unknownTypeError(unknown))
        }
        .decoder
    }

    val extendedPolicyDecoder = {
      Decoder
        .instance { c =>
          for {
            policyType <- c.downFieldAs[String]("policy")
            policy <- policyType match {
              case "allow"  => Right(Block.Policy.Allow)
              case "forbid" => c.downFieldAs[Option[String]]("response_message").map(Block.Policy.Forbid.apply)
              case unknown  => Left(decodingFailureFrom(unknownTypeError(unknown)))
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
        case Some(_) | None        => Left(DecodingFailure("Malformed block policy type", c.history))
      }
    }
  }

  private def auditSettingsDecoder(
      capabilities: EsAuditCapabilities
  )(esEnv: EsEnv): Decoder[Option[CoreCreationResult.AuditSetup]] = capabilities match {
    case cap: EsAuditCapabilities.Index =>
      AuditingSettingsDecoder
        .legacy(esEnv)
        .map(_.map(settings => new CoreCreationResult.AuditSetup.Index(cap, settings)))
    case cap: EsAuditCapabilities.IndexWithDataStream =>
      AuditingSettingsDecoder
        .standard(esEnv)
        .map(_.map(settings => new CoreCreationResult.AuditSetup.IndexWithDataStream(cap, settings)))
  }

  private def coreDecoder(
      httpClientFactory: HttpClientsFactory,
      ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
      globalSettings: GlobalSettings,
      mocksProvider: MocksProvider,
      auditCapabilities: EsAuditCapabilities
  ): AsyncDecoder[CoreCreationResult] = {
    AsyncDecoderCreator.instance[CoreCreationResult] { c =>
      val decoder = for {
        obfuscatedHeaders <- AsyncDecoderCreator.from(obfuscatedHeadersAsyncDecoder)
        loggingContext = LoggingContext(obfuscatedHeaders)
        dynamicVariableTransformationAliases <-
          AsyncDecoderCreator.from(
            VariableTransformationAliasesDefinitionsDecoder.create(systemContext.variablesFunctions)
          )
        variableCreator = new RuntimeResolvableVariableCreator(
          TransformationCompiler.withAliases(
            systemContext.variablesFunctions,
            dynamicVariableTransformationAliases.items.map(_.alias)
          )
        )
        auditingConfig <- AsyncDecoderCreator.from(auditSettingsDecoder(auditCapabilities)(esEnv))
        authProxies <- AsyncDecoderCreator.from(ProxyAuthDefinitionsDecoder.instance)
        authenticationServices <- AsyncDecoderCreator.from(
          ExternalAuthenticationServicesDecoder.instance(httpClientFactory)
        )
        externalGroupsProviderServices <- AsyncDecoderCreator.from(
          ExternalGroupsProviderServicesDecoder.instance(httpClientFactory)
        )
        jwtDefs <- AsyncDecoderCreator.from(JwtDefinitionsDecoder.instance(httpClientFactory, variableCreator))
        ldapServices <- LdapServicesDecoder.ldapServicesDefinitionsDecoder(
          using ldapConnectionPoolProvider,
          systemContext.clock
        )
        rorKbnDefs <- AsyncDecoderCreator.from(RorKbnDefinitionsDecoder.instance(variableCreator))
        impersonationDefinitionsDecoderCreator = new ImpersonationDefinitionsDecoderCreator(
          globalSettings,
          authenticationServices,
          authProxies,
          ldapServices,
          mocksProvider,
          esEnv
        )
        impersonationDefs <- AsyncDecoderCreator.from(impersonationDefinitionsDecoderCreator.create)
        userDefs <- AsyncDecoderCreator.from(
          UsersDefinitionsDecoder.instance(
            authenticationServices,
            externalGroupsProviderServices,
            authProxies,
            jwtDefs,
            rorKbnDefs,
            ldapServices,
            Some(impersonationDefs),
            mocksProvider,
            globalSettings,
            esEnv
          )
        )
        blocksNel <- {
          implicit val loggingContext: LoggingContext = LoggingContext(obfuscatedHeaders)
          implicit val blockAsyncDecoder: AsyncDecoder[BlockDecodingResult] = AsyncDecoderCreator.from {
            blockDecoder(
              DefinitionsPack(
                proxies = authProxies,
                users = userDefs,
                authenticationServices = authenticationServices,
                externalGroupsProviderServices = externalGroupsProviderServices,
                jwts = jwtDefs,
                rorKbns = rorKbnDefs,
                ldaps = ldapServices,
                impersonators = impersonationDefs,
                variableTransformationAliases = dynamicVariableTransformationAliases,
              ),
              globalSettings,
              mocksProvider,
              esEnv
            )
          }
          DecoderHelpers
            .decodeFieldList[BlockDecodingResult, Task](Attributes.acl, RulesLevelCreationError.apply)
            .emapE { result =>
              AclValidator
                .validate(result.toOption, userDefs)
                .leftMap(msgs => BlocksLevelCreationError(Message(msgs.toList.mkString("; "))))
                .toEither
            }
        }
        _ <- auditSinkNamesDecoder(blocksNel, auditingConfig)
      } yield {
        val blocks = blocksNel.map(_.block)
        blocks.toList.foreach { block => noRequestIdLogger.info(s"ADDING BLOCK:\t ${block.show}") }

        blocksNel.map(_.block).filter(_.audit == Block.Audit.Disabled).toNel.foreach { blocks =>
          noRequestIdLogger.debug(
            s"Blocks [${blocks.map(_.name.value).toList.mkString(", ")}] have 'audit: disabled', which suppresses ALL audit output including the default ACL log. " +
              s"To keep ACL log visibility while silencing other sinks, use 'enabled_audit_sinks: [${SinkName.defaultAclLog.value}]' instead."
          )
        }

        val localUsers: LocalUsers = blocksNel.map(_.localUsers).toList.combineAll

        val rorDependencies = RorDependencies(
          services = RorDependencies.Services(
            authenticationServices = authenticationServices.items.map(_.id),
            externalGroupsProviderServices = externalGroupsProviderServices.items.map(_.id),
            ldaps = ldapServices.items.map(_.id)
          ),
          localUsers = localUsers,
          impersonationWarningsReader =
            new ImpersonationWarningsCombinedReader(blocksNel.map(_.impersonationWarnings).toList: _*),
        )
        import systemContext.scheduler
        val accessControl = new EnabledAccessControlList(
          blocks,
          new AccessControlListStaticContext(
            blocks,
            globalSettings,
            obfuscatedHeaders
          )
        ): AccessControlList
        new CoreCreationResult(Core(accessControl, rorDependencies, auditingConfig.map(_.settings)), auditingConfig)
      }
      decoder.apply(c)
    }
  }

}

object RawRorSettingsBasedCoreFactory {

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

      final case class MalformedValue private (value: String) extends Reason

      object MalformedValue {

        def fromString(raw: String): MalformedValue = {
          val normalized = raw.replaceAll("\r\n?", "\n")
          MalformedValue(normalized)
        }

        def apply(json: Json): MalformedValue = from(json)

        def from(json: Json): MalformedValue = MalformedValue {
          YamlOps.jsonToYamlString(json)
        }

      }

    }

  }

  private class ImpersonationWarningsCombinedReader(readers: ImpersonationWarningsReader*)
      extends ImpersonationWarningsReader {

    override def read()(
        implicit requestId: RequestId
    ): List[ImpersonationWarning] = readers.flatMap(_.read()).toList

  }

  private class BlockImpersonationWarningsReader[R <: Rule](
      blockName: Block.Name,
      blockRules: NonEmptyList[RuleDefinition[R]]
  ) extends ImpersonationWarningsReader {

    override def read()(
        implicit request: RequestId
    ): List[ImpersonationWarning] = {
      blockRules.toList
        .flatMap(impersonationWarningForRule(_))
    }

    private def impersonationWarningForRule(rule: RuleDefinition[R])(
        implicit requestId: RequestId
    ): List[ImpersonationWarning] = {
      rule.impersonationWarnings match {
        case extractor: ImpersonationWarningSupport.ImpersonationWarningExtractor[_] =>
          extractor.warningFor(rule.rule, blockName).toList
        case ImpersonationWarningSupport.NotSupported() =>
          List.empty
      }
    }

  }

  private[factory] case class BlockDecodingResult(
      block: Block,
      localUsers: LocalUsers,
      impersonationWarnings: ImpersonationWarningsReader
  )

  private sealed trait RuleDecodingResult

  private object RuleDecodingResult {
    final case class Result(value: Decoder.Result[RuleDefinition[Rule]]) extends RuleDecodingResult

    case object UnknownRule extends RuleDecodingResult

    case object Skipped extends RuleDecodingResult
  }

  private[factory] object Attributes {
    val rorSectionName = "readonlyrest"
    val acl = "access_control_rules"

    object Block {
      val name = "name"
      val policy = "type"
      val verbosity = "verbosity"
      val audit = "audit"
    }

  }

}
