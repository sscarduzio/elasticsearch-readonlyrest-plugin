package tech.beshu.ror.acl.factory

import cats.data.NonEmptyList
import io.circe.yaml.parser
import io.circe.{Decoder, DecodingFailure, Json}
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.Verbosity
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{BlockInstantiatingError, UnparsableYamlContent}
import tech.beshu.ror.acl.factory.RorAclFactory.{AclCreationError, Attributes}
import tech.beshu.ror.acl.factory.ruleDecoders.ruleDecoder
import tech.beshu.ror.acl.utils.CirceOps.{DecoderOps, DecodingFailureOps}
import tech.beshu.ror.acl.{Acl, SequentialAcl}

import scala.language.implicitConversions

class RorAclFactory {

  def createAclFrom(settingsYamlString: String): Either[AclCreationError, Acl] = {
    parser.parse(settingsYamlString) match {
      case Right(json) => createFrom(json)
      case Left(_) => Left(UnparsableYamlContent(settingsYamlString))
    }
  }

  private def createFrom(settingsJson: Json) = {
    aclDecoder
      .decodeJson(settingsJson)
      .left
      .map(decodingFailure => decodingFailure.aclCreationError.getOrElse(BlockInstantiatingError(decodingFailure.message)))
  }

  private implicit val rulesListDecoder: Decoder[NonEmptyList[Rule]] = Decoder.instance { c =>
    def decodeRule(name: String): Decoder.Result[Rule] = {
      // todo: filter associated keys; what if one of these key will be at the begining?
      val decoder = ruleDecoder(name)
      decoder.decode(
        c.downField(name),
        c.value.mapObject(_.filterKeys(key => decoder.associatedFields.contains(key)))
      )
    }
    c.keys.map(_.toList) match {
      case None | Some(Nil) => Left(DecodingFailure(s"No rules found inside the block", Nil))
      case Some(firstRuleName :: ruleNames) =>
        ruleNames.foldLeft(decodeRule(firstRuleName).map(NonEmptyList.one)) {
          case (acc, currentRuleName) =>
            acc.flatMap(nelOfRules =>
              decodeRule(currentRuleName).map(_ :: nelOfRules)
            )
        }
    }
  }

  private implicit val blockDecoder: Decoder[Block] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderOps.decodeStringOrNumber.map(Block.Name.apply)
    implicit val policyDecoder: Decoder[Block.Policy] = Decoder.decodeString.emap {
      case "allow" => Right(Block.Policy.Allow)
      case "forbid" => Right(Block.Policy.Forbid)
      case unknown => Left(s"Unknown block policy type: $unknown")
    }
    implicit val verbosityDecoder: Decoder[Verbosity] = Decoder.decodeString.emap {
      case "info" => Right(Verbosity.Info)
      case "error" => Right(Verbosity.Error)
      case unknown => Left(s"Unknown verbosity value: $unknown")
    }
    Decoder
      .instance { c =>
        val result = for {
          name <- c.downField(Attributes.Block.name).as[Block.Name]
          policy <- c.downField(Attributes.Block.policy).as[Option[Block.Policy]]
          verbosity <- c.downField(Attributes.Block.verbosity).as[Option[Block.Verbosity]]
          rules <- Decoder[NonEmptyList[Rule]].tryDecode(c.withFocus(
            _.mapObject(_
              .remove(Attributes.Block.name)
              .remove(Attributes.Block.policy)
              .remove(Attributes.Block.verbosity))
          ))
        } yield new Block(
          name,
          policy.getOrElse(Block.Policy.Allow),
          verbosity.getOrElse(Block.Verbosity.Info),
          rules
        )
        result.left.map(_.overrideDefaultErrorWith(AclCreationError.BlockInstantiatingError(s"Cannot load block section [${c.value}]")))
      }
  }

  private implicit val aclDecoder: Decoder[Acl] = Decoder.instance(_
    .downField(Attributes.acl).as[NonEmptyList[Block]]
    .map(new SequentialAcl(_))
    .left.map(_.overrideDefaultErrorWith(BlockInstantiatingError(s"Cannot load ${Attributes.acl} section")))
  )
}

object RorAclFactory {

  sealed trait AclCreationError
  object AclCreationError {
    final case class UnparsableYamlContent(value: String) extends AclCreationError
    final case class BlockInstantiatingError(message: String) extends AclCreationError
    final case class RuleInstantiatingError(message: String) extends AclCreationError
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
