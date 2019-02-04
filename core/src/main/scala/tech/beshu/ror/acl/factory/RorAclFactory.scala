package tech.beshu.ror.acl.factory

import java.time.Clock

import cats.data.{NonEmptyList, State}
import cats.implicits._
import cats.kernel.Monoid
import io.circe._
import io.circe.yaml.parser
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.Verbosity
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError._
import tech.beshu.ror.acl.factory.RorAclFactory.{AclCreationError, Attributes}
import tech.beshu.ror.acl.factory.decoders.definitions._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers.FieldListResult.{FieldListValue, NoField}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, DecoderOps, DecodingFailureOps}
import tech.beshu.ror.acl.utils.ScalaOps._
import tech.beshu.ror.acl.utils.{StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.acl.{Acl, SequentialAcl}

import scala.language.implicitConversions

class RorAclFactory(implicit clock: Clock,
                    uuidProvider: UuidProvider,
                    resolver: StaticVariablesResolver)
  extends Logging {

  def createAclFrom(settingsYamlString: String,
                    httpClientFactory: HttpClientsFactory): Either[NonEmptyList[AclCreationError], Acl] = {
    parser.parse(settingsYamlString) match {
      case Right(json) =>
        createFrom(json, httpClientFactory)
          .toEither
          .left.map { failures =>
          failures.map(f => f.aclCreationError.getOrElse(BlocksLevelCreationError(Message(s"Malformed:\n$settingsYamlString"))))
        }
      case Left(_) =>
        Left(NonEmptyList.one(UnparsableYamlContent(Message(s"Malformed: $settingsYamlString"))))
    }
  }

  private def createFrom(settingsJson: Json, httpClientFactory: HttpClientsFactory) = {
    val rorSectionName = "readonlyrest"
    val decoder: AccumulatingDecoder[Acl] = AccumulatingDecoder.instance { c =>
      c.downField(rorSectionName).success match {
        case Some(rorCursor) =>
          aclDecoder(httpClientFactory).apply(rorCursor)
        case None =>
          val failure = DecodingFailureOps.fromError(ReadonlyrestSettingsCreationError(Message(s"No $rorSectionName section found")))
          AccumulatingDecoder
            .failed[Acl](NonEmptyList.one(failure))
            .apply(c)
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

  private implicit def blockDecoder(definitions: DefinitionsPack): Decoder[Block] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderHelpers.decodeStringLike.map(Block.Name.apply)
    implicit val policyDecoder: Decoder[Block.Policy] = Decoder.decodeString.emapE {
      case "allow" => Right(Block.Policy.Allow)
      case "forbid" => Right(Block.Policy.Forbid)
      case unknown => Left(BlocksLevelCreationError(Message(s"Unknown block policy type: $unknown")))
    }
    implicit val verbosityDecoder: Decoder[Verbosity] = Decoder.decodeString.emapE {
      case "info" => Right(Verbosity.Info)
      case "error" => Right(Verbosity.Error)
      case unknown => Left(BlocksLevelCreationError(Message(s"Unknown verbosity value: $unknown")))
    }
    Decoder
      .instance { c =>
        val result = for {
          name <- c.downField(Attributes.Block.name).as[Block.Name]
          policy <- c.downField(Attributes.Block.policy).as[Option[Block.Policy]]
          verbosity <- c.downField(Attributes.Block.verbosity).as[Option[Block.Verbosity]]
          rules <- rulesNelDecoder(definitions).tryDecode(c.withFocus(
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


  private implicit def aclDecoder(httpClientFactory: HttpClientsFactory): AccumulatingDecoder[Acl] =
    AccumulatingDecoder.instance { c =>
      val decoder = for {
        authProxies <- new ProxyAuthDefinitionsDecoder
        authenticationServices <- new ExternalAuthenticationServicesDecoder(httpClientFactory)
        authorizationServices <- new ExternalAuthorizationServicesDecoder(httpClientFactory)
        jwtDefs <- new JwtDefinitionsDecoder(httpClientFactory, resolver)
        rorKbnDefs <- new RorKbnDefinitionsDecoder(resolver)
        userDefs <- new UsersDefinitionsDecoder(authenticationServices, authProxies, jwtDefs, rorKbnDefs)
        acl <- {
          implicit val _ = blockDecoder(DefinitionsPack(authProxies, userDefs, authenticationServices, authorizationServices, jwtDefs, rorKbnDefs))
          DecoderHelpers
            .decodeFieldList[Block](Attributes.acl, RulesLevelCreationError.apply)
            .emapE {
              case NoField => Left(BlocksLevelCreationError(Message(s"No ${Attributes.acl} section found")))
              case FieldListValue(blocks) =>
                NonEmptyList.fromList(blocks) match {
                  case None => Left(BlocksLevelCreationError(Message(s"${Attributes.acl} defined, but no block found")))
                  case Some(neBlocks) =>
                    neBlocks.map(_.name).toList.findDuplicates match {
                      case Nil => Right(neBlocks)
                      case duplicates => Left(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: ${duplicates.map(_.show).mkString(",")}")))
                    }
                }
            }
            .map { blocks =>
              blocks.map { block =>
                logger.info("ADDING BLOCK:\t" + block.show)
              }
              new SequentialAcl(blocks): Acl
            }
        }
      } yield acl
      decoder.accumulating.apply(c)
    }
}

object RorAclFactory {

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

    sealed trait Reason
    object Reason {
      final case class Message(value: String) extends Reason
      final case class MalformedValue private(value: String) extends Reason
      object MalformedValue {
        def apply(json: Json): MalformedValue = from(json)

        def from(json: Json): MalformedValue = MalformedValue {
          io.circe.yaml.printer.print(json)
        }
      }
    }

  }

  private object Attributes {
    val acl = "access_control_rules"

    object Block {
      val name = "name"
      val policy = "type"
      val verbosity = "verbosity"
    }

  }

}
