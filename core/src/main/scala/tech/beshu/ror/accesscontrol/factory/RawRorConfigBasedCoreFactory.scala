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

import java.time.Clock

import cats.data.{NonEmptyList, State}
import cats.implicits._
import cats.kernel.Monoid
import io.circe._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol._
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.Verbosity
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.{AclCreationError, Attributes}
import tech.beshu.ror.accesscontrol.factory.decoders.{AuditingSettingsDecoder, GlobalStaticSettingsDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions._
import tech.beshu.ror.accesscontrol.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.accesscontrol.logging.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, DecodingFailureOps, _}
import tech.beshu.ror.accesscontrol.utils._
import tech.beshu.ror.boot.RorMode
import tech.beshu.ror.configuration.loader.RorConfigurationIndex
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.providers.{EnvVarsProvider, UuidProvider}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.yaml.YamlOps

final case class CoreSettings(aclEngine: AccessControl,
                              aclStaticContext: AccessControlStaticContext,
                              auditingSettings: Option[AuditingTool.Settings])

trait CoreFactory {
  def createCoreFrom(config: RawRorConfig,
                     rorIndexNameConfiguration: RorConfigurationIndex,
                     httpClientFactory: HttpClientsFactory,
                     ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): Task[Either[NonEmptyList[AclCreationError], CoreSettings]]
}

class RawRorConfigBasedCoreFactory(rorMode: RorMode)
                                  (implicit clock: Clock,
                                   uuidProvider: UuidProvider,
                                   envVarProvider: EnvVarsProvider)
  extends CoreFactory with Logging {

  override def createCoreFrom(config: RawRorConfig,
                              rorIndexNameConfiguration: RorConfigurationIndex,
                              httpClientFactory: HttpClientsFactory,
                              ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider): Task[Either[NonEmptyList[AclCreationError], CoreSettings]] = {
    config.configJson \\ Attributes.rorSectionName match {
      case Nil => createCoreFromRorSection(config.configJson, rorIndexNameConfiguration, httpClientFactory, ldapConnectionPoolProvider)
      case rorSection :: Nil => createCoreFromRorSection(rorSection, rorIndexNameConfiguration, httpClientFactory, ldapConnectionPoolProvider)
      case _ => Task.now(Left(NonEmptyList.one(GeneralReadonlyrestSettingsError(Message(s"Malformed settings")))))
    }
  }

  private def createCoreFromRorSection(rorSection: Json,
                                       rorIndexNameConfiguration: RorConfigurationIndex,
                                       httpClientFactory: HttpClientsFactory,
                                       ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider) = {
    JsonConfigStaticVariableResolver.resolve(rorSection) match {
      case Right(resolvedRorSection) =>
        createFrom(resolvedRorSection, rorIndexNameConfiguration, httpClientFactory, ldapConnectionPoolProvider).map {
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
                         rorIndexNameConfiguration: RorConfigurationIndex,
                         httpClientFactory: HttpClientsFactory,
                         ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider) = {
    val decoder = for {
      enabled <- AsyncDecoderCreator.from(coreEnabilityDecoder)
      core <-
      if (!enabled) {
        AsyncDecoderCreator
          .from(Decoder.const(CoreSettings(DisabledAccessControl, DisabledAccessControlStaticContext$, None)))
      } else {
        for {
          globalSettings <- AsyncDecoderCreator.from(GlobalStaticSettingsDecoder.instance(rorMode))
          aclAndContext <- aclDecoder(httpClientFactory, ldapConnectionPoolProvider, rorIndexNameConfiguration, globalSettings)
          (acl, context) = aclAndContext
          auditingTools <- AsyncDecoderCreator.from(AuditingSettingsDecoder.instance)
        } yield CoreSettings(
          aclEngine = acl,
          aclStaticContext = context,
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

  private implicit def rulesNelDecoder(definitions: DefinitionsPack,
                                       rorIndexNameConfiguration: RorConfigurationIndex,
                                       globalSettings: GlobalSettings): Decoder[NonEmptyList[RuleWithVariableUsageDefinition[Rule]]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Option[Decoder.Result[List[RuleWithVariableUsageDefinition[Rule]]]]](None)
    val (cursor, result) = c.keys.toList.flatten.sorted // at the moment kibana_index must be defined before kibana_access
      .foldLeft(init) { case (collectedRuleResults, currentRuleName) =>
        for {
          last <- collectedRuleResults
          current <- decodeRuleInCursorContext(currentRuleName, definitions, rorIndexNameConfiguration, globalSettings).map(_.map(_.map(_ :: Nil)))
        } yield Monoid.combine(last, current)
      }
      .run(c)
      .value

    (cursor.keys.toList.flatten, result) match {
      case (Nil, Some(r)) =>
        r.flatMap { a =>
          NonEmptyList.fromList(a) match {
            case Some(rules) =>
              Right(rules)
            case None =>
              Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"No rules defined in block"))))
          }
        }
      case (Nil, None) =>
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"No rules defined in block"))))
      case (keys, _) =>
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message(s"Unknown rules: ${keys.mkString(",")}"))))
    }
  }

  private def decodeRuleInCursorContext(name: String,
                                        definitions: DefinitionsPack,
                                        rorIndexNameConfiguration: RorConfigurationIndex,
                                        globalSettings: GlobalSettings): State[ACursor, Option[Decoder.Result[RuleWithVariableUsageDefinition[Rule]]]] =
    State(cursor => {
      if (!cursor.keys.exists(_.toSet.contains(name))) (cursor, None)
      else {
        ruleDecoderBy(Rule.Name(name), definitions, rorIndexNameConfiguration, globalSettings) match {
          case Some(decoder) =>
            val decodingResult = decoder.decode(
              cursor.downField(name),
              cursor.withFocus(_.mapObject(_.filterKeys(key => decoder.associatedFields.contains(key))))
            )
            val newCursor = cursor.withFocus(_.mapObject(json =>
              decoder
                .associatedFields
                .foldLeft(json.remove(name)) {
                  case (currentJson, field) =>
                    ruleDecoderBy(Rule.Name(field), definitions, rorIndexNameConfiguration, globalSettings) match {
                      case Some(_) => currentJson
                      case None => currentJson.remove(field)
                    }
                }
            ))
            (newCursor, Some(decodingResult))
          case None =>
            (cursor, None)
        }
      }
    })

  private def blockDecoder(definitions: DefinitionsPack,
                           rorIndexNameConfiguration: RorConfigurationIndex,
                           globalSettings: GlobalSettings)
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
          rules <- rulesNelDecoder(definitions, rorIndexNameConfiguration, globalSettings)
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

  private def aclStaticContextCreator(blocks: NonEmptyList[Block],
                                      obfuscatedHeaders:Set[Header.Name],
                                      globalSettings: GlobalSettings): EnabledAccessControlStaticContext = {
      new EnabledAccessControlStaticContext(
        blocks,
        globalSettings,
        obfuscatedHeaders
      )
  }

  private val obfuscatedHeadersAsyncDecoder: Decoder[Set[Header.Name]] = {
    import tech.beshu.ror.accesscontrol.factory.decoders.common.headerName
    Decoder.instance(_.downField("obfuscated_headers").as[Option[Set[Header.Name]]])
      .map(_.getOrElse(Set(Header.Name.authorization)))
  }

  private def aclDecoder(httpClientFactory: HttpClientsFactory,
                         ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider,
                         rorIndexNameConfiguration: RorConfigurationIndex,
                         globalSettings: GlobalSettings): AsyncDecoder[(AccessControl, EnabledAccessControlStaticContext)] =
    AsyncDecoderCreator.instance[(AccessControl, EnabledAccessControlStaticContext)] { c =>
      val decoder = for {
        authProxies <- AsyncDecoderCreator.from(ProxyAuthDefinitionsDecoder.instance)
        authenticationServices <- AsyncDecoderCreator.from(ExternalAuthenticationServicesDecoder.instance(httpClientFactory))
        authorizationServices <- AsyncDecoderCreator.from(ExternalAuthorizationServicesDecoder.instance(httpClientFactory))
        jwtDefs <- AsyncDecoderCreator.from(JwtDefinitionsDecoder.instance(httpClientFactory))
        ldapServices <- LdapServicesDecoder.ldapServicesDefinitionsDecoder(ldapConnectionPoolProvider)
        rorKbnDefs <- AsyncDecoderCreator.from(RorKbnDefinitionsDecoder.instance())
        impersonationDefs <- AsyncDecoderCreator.from(ImpersonationDefinitionsDecoder.instance(authenticationServices, authProxies, jwtDefs, ldapServices, rorKbnDefs))
        userDefs <- AsyncDecoderCreator.from(UsersDefinitionsDecoder.instance(authenticationServices, authProxies, jwtDefs, ldapServices, rorKbnDefs, impersonationDefs))
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
              rorIndexNameConfiguration,
              globalSettings
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
        staticContext = aclStaticContextCreator(blocks, obfuscatedHeaders, globalSettings)
        acl = {
          implicit val loggingContext: LoggingContext = LoggingContext(obfuscatedHeaders)
          val upgradedBlocks = CrossBlockContextBlocksUpgrade.upgrade(blocks)
          upgradedBlocks.toList.foreach { block => logger.info("ADDING BLOCK:\t" + block.show) }
          new AccessControlList(upgradedBlocks): AccessControl
        }
      } yield (acl, staticContext)
      decoder.apply(c)
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
