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
package tech.beshu.ror.acl.factory

import java.time.Clock

import cats.data.{NonEmptyList, State}
import cats.implicits._
import cats.kernel.Monoid
import io.circe._
import io.circe.yaml.parser
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.yaml.snakeyaml.Yaml
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.Verbosity
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError._
import tech.beshu.ror.acl.factory.CoreFactory.{AclCreationError, Attributes}
import tech.beshu.ror.acl.factory.RulesValidator.ValidationError
import tech.beshu.ror.acl.factory.decoders.AuditingSettingsDecoder
import tech.beshu.ror.acl.factory.decoders.definitions._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.acl.logging.AuditingTool
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.acl.utils.ScalaOps._
import tech.beshu.ror.acl.utils._
import tech.beshu.ror.acl.{Acl, AclStaticContext, SequentialAcl}
import tech.beshu.ror.acl.utils.CirceOps._

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

final case class CoreSettings(aclEngine: Acl, aclStaticContext: AclStaticContext, auditingSettings: Option[AuditingTool.Settings])

class CoreFactory(implicit clock: Clock,
                  uuidProvider: UuidProvider,
                  resolver: StaticVariablesResolver)
  extends Logging {

  def createCoreFrom(settingsYamlString: String,
                     httpClientFactory: HttpClientsFactory): Task[Either[NonEmptyList[AclCreationError], CoreSettings]] = {
    trimToRorPartOnly(settingsYamlString) match {
      case Right(rorPartYamlString) =>
        parser.parse(rorPartYamlString) match {
          case Right(json) =>
            createFrom(json, httpClientFactory).map {
              case Right(settings) =>
                Right(settings)
              case Left(failure) =>
                Left(NonEmptyList.one(failure.aclCreationError.getOrElse(BlocksLevelCreationError(Message(s"Malformed:\n$settingsYamlString")))))
            }
          case Left(ex) =>
            logger.debug("Unparsable yaml", ex)
            Task.now(Left(NonEmptyList.one(UnparsableYamlContent(Message(s"Malformed: $settingsYamlString")))))
        }
      case Left(error) =>
        Task.now(Left(NonEmptyList.one(error)))
    }
  }

  private def trimToRorPartOnly(settingsYamlString: String): Either[AclCreationError, String] = {
    val yaml = new Yaml()
    Try(yaml.load[java.util.Map[String, Object]](settingsYamlString)) match {
      case Success(map) =>
        Option(map.get(Attributes.rorSectionName)).map(yaml.dump) match {
          case Some(value) => Right(value)
          case None => Left(ReadonlyrestSettingsCreationError(Message(s"No ${Attributes.rorSectionName} section found")))
        }
      case Failure(ex) =>
        logger.debug("Unparsable yaml", ex)
        Left(UnparsableYamlContent(Message(s"Malformed: $settingsYamlString")))
    }
  }

  private def createFrom(settingsJson: Json, httpClientFactory: HttpClientsFactory) = {
    val decoder = aclDecoder(httpClientFactory)
      .flatMap { case (acl, context) =>
        AsyncDecoderCreator.from(AuditingSettingsDecoder.instance)
          .map { auditingTools =>
            CoreSettings(acl, context, auditingTools)
          }
      }
    decoder(HCursor.fromJson(settingsJson))
  }

  private implicit def rulesNelDecoder(definitions: DefinitionsPack): Decoder[NonEmptyList[Rule]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Option[Decoder.Result[List[Rule]]]](None)
    val (cursor, result) = c.keys.toList.flatten
      .foldLeft(init) { case (collectedRuleResults, currentRuleName) =>
        for {
          last <- collectedRuleResults
          current <- decodeRuleInCursorContext(currentRuleName, definitions).map(_.map(_.map(_ :: Nil)))
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

  private def decodeRuleInCursorContext(name: String, definitions: DefinitionsPack): State[ACursor, Option[Decoder.Result[Rule]]] =
    State(cursor => {
      if (!cursor.keys.exists(_.toSet.contains(name))) (cursor, None)
      else {
        ruleDecoderBy(Rule.Name(name), definitions) match {
          case Some(decoder) =>
            val decodingResult = decoder.decode(
              cursor.downField(name),
              cursor.withFocus(_.mapObject(_.filterKeys(key => decoder.associatedFields.contains(key))))
            )
            val newCursor = cursor.withFocus(_.mapObject(json => decoder.associatedFields.foldLeft(json.remove(name)) {
              _.remove(_)
            }))
            (newCursor, Some(decodingResult))
          case None =>
            (cursor, None)
        }
      }
    })

  private def rulesDecoder(blockName: Block.Name, definitions: DefinitionsPack): Decoder[NonEmptyList[Rule]] = {
    rulesNelDecoder(definitions)
      .toSyncDecoder
      .emapE { rules =>
        RulesValidator.validate(rules) match {
          case Right(_) => Right(rules)
          case Left(ValidationError.AuthorizationWithoutAuthentication) =>
            Left(BlocksLevelCreationError(Message(s"The '${blockName.show}' block contains an authorization rule, but not an authentication rule. This does not mean anything if you don't also set some authentication rule.")))
        }
      }
      .decoder
  }

  private implicit def blockDecoder(definitions: DefinitionsPack): Decoder[Block] = {
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
          rules <- rulesDecoder(name, definitions)
            .tryDecode(c.withFocus(
              _.mapObject(_
                .remove(Attributes.Block.name)
                .remove(Attributes.Block.policy)
                .remove(Attributes.Block.verbosity))
            ))
        } yield new Block(
          name,
          policy.getOrElse(Block.Policy.Allow),
          verbosity.getOrElse(Block.Verbosity.Info),
          rules.sorted
        )
        result.left.map(_.overrideDefaultErrorWith(BlocksLevelCreationError(MalformedValue(c.value))))
      }
  }

  private def aclStaticContextDecoder(blocks: NonEmptyList[Block]): Decoder[AclStaticContext] = {
    Decoder.instance { c =>
      for {
        basicAuthPrompt <- c.downField("prompt_for_basic_auth").as[Option[Boolean]]
      } yield new AclStaticContext(blocks, basicAuthPrompt.getOrElse(true))
    }
  }

  private implicit def aclDecoder(httpClientFactory: HttpClientsFactory): AsyncDecoder[(Acl, AclStaticContext)] =
    AsyncDecoderCreator.instance[(Acl, AclStaticContext)] { c =>
      val decoder = for {
        authProxies <- AsyncDecoderCreator.from(ProxyAuthDefinitionsDecoder.instance)
        authenticationServices <- AsyncDecoderCreator.from(ExternalAuthenticationServicesDecoder.instance(httpClientFactory))
        authorizationServices <- AsyncDecoderCreator.from(ExternalAuthorizationServicesDecoder.instance(httpClientFactory))
        jwtDefs <- AsyncDecoderCreator.from(JwtDefinitionsDecoder.instance(httpClientFactory, resolver))
        ldapServices <- LdapServicesDecoder.ldapServicesDefinitionsDecoder
        rorKbnDefs <- AsyncDecoderCreator.from(RorKbnDefinitionsDecoder.instance(resolver))
        userDefs <- AsyncDecoderCreator.from(UsersDefinitionsDecoder.instance(authenticationServices, authProxies, jwtDefs, ldapServices, rorKbnDefs))
        blocks <- {
          implicit val blockAsyncDecoder: AsyncDecoder[Block] = AsyncDecoderCreator.from {
            blockDecoder(DefinitionsPack(authProxies, userDefs, authenticationServices, authorizationServices, jwtDefs, rorKbnDefs, ldapServices))
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
        staticContext <- AsyncDecoderCreator.from(aclStaticContextDecoder(blocks))
        acl = {
          val upgradedBlocks = CrossBlockContextBlocksUpgrade.upgrade(blocks)
          upgradedBlocks.toList.foreach { block => logger.info("ADDING BLOCK:\t" + block.show) }
          new SequentialAcl(upgradedBlocks): Acl
        }
      } yield (acl, staticContext)
      decoder.apply(c)
    }
}

object CoreFactory {

  sealed trait AclCreationError {
    def reason: Reason
  }

  object AclCreationError {

    final case class UnparsableYamlContent(reason: Reason) extends AclCreationError
    final case class ReadonlyrestSettingsCreationError(reason: Reason) extends AclCreationError
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
