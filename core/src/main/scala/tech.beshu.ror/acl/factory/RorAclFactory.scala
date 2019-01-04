package tech.beshu.ror.acl.factory

import java.time.Clock

import cats.data.{NonEmptyList, State}
import cats.implicits._
import cats.kernel.Monoid
import io.circe.yaml.parser
import io.circe._
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.Verbosity
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{BlocksLevelCreationError, RulesLevelCreationError, UnparsableYamlContent}
import tech.beshu.ror.acl.factory.RorAclFactory.{AclCreationError, Attributes}
import tech.beshu.ror.acl.factory.decoders.ProxyAuth
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.ruleDecoderBy
import tech.beshu.ror.acl.utils.CirceOps.{DecoderOps, DecodingFailureOps}
import tech.beshu.ror.acl.utils.{JavaUuidProvider, UuidProvider}
import tech.beshu.ror.acl.{Acl, SequentialAcl}
import tech.beshu.ror.commons.aDomain.Header

import scala.language.implicitConversions

class RorAclFactory {

  implicit val clock: Clock = Clock.systemUTC() // todo:
  implicit val uuidProvider: UuidProvider = JavaUuidProvider // todo:

  def createAclFrom(settingsYamlString: String): Either[NonEmptyList[AclCreationError], Acl] = {
    parser.parse(settingsYamlString) match {
      case Right(json) => createFrom(json).toEither
      case Left(_) => Left(NonEmptyList.one(UnparsableYamlContent(settingsYamlString)))
    }
  }

  private def createFrom(settingsJson: Json) = {
    aclDecoder(HCursor.fromJson(settingsJson))
      .leftMap { failures =>
        failures.map(f => f.aclCreationError.getOrElse(BlocksLevelCreationError(f.message)))
      }
  }

  private implicit def rulesNelDecoder(authProxies: Set[ProxyAuth]): Decoder[NonEmptyList[Rule]] = Decoder.instance { c =>
    val init = State.pure[ACursor, Option[Decoder.Result[List[Rule]]]](None)
    val (cursor, result) = c.keys.toList.flatten
      .foldLeft(init) { case (collectedRuleResults, currentRuleName) =>
        for {
          last <- collectedRuleResults
          current <- decodeRuleInCursorContext(currentRuleName, authProxies).map(_.map(_.map(_ :: Nil)))
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
              Left(DecodingFailureOps.fromError(RulesLevelCreationError(s"No rules defined in block")))
          }
        }
      case (Nil, None) =>
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(s"No rules defined in block")))
      case (keys, _) =>
        Left(DecodingFailureOps.fromError(RulesLevelCreationError(s"Unknown rules: ${keys.mkString(",")}")))
    }
  }

  private def decodeRuleInCursorContext(name: String, authProxies: Set[ProxyAuth]): State[ACursor, Option[Decoder.Result[Rule]]] =
    State(cursor => {
      ruleDecoderBy(name, authProxies) match {
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
    })

  private implicit def blockDecoder(authProxies: Set[ProxyAuth]): Decoder[Block] = {
    implicit val nameDecoder: Decoder[Block.Name] = DecoderOps.decodeStringLike.map(Block.Name.apply)
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
          rules <- rulesNelDecoder(authProxies).tryDecode(c.withFocus(
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
        result.left.map(_.overrideDefaultErrorWith(AclCreationError.BlocksLevelCreationError(s"Cannot load block section [${c.value}]")))
      }
  }

  private implicit val proxyAuthDecoder: Decoder[ProxyAuth] = {
    implicit val nameDecoder: Decoder[ProxyAuth.Name] = Decoder.decodeString.map(ProxyAuth.Name.apply)
    implicit val headerNameDecoder: Decoder[Header.Name] = Decoder.decodeString.map(Header.Name.apply)
    Decoder.forProduct2("name", "user_id_header")(ProxyAuth.apply)
  }

  private implicit val aclDecoder: AccumulatingDecoder[Acl] = AccumulatingDecoder.instance { c =>
    val decoder = for {
      authProxies <- Decoder.forProduct1("proxy_auth_configs")(Set.apply[ProxyAuth])
      acl <- {
        implicit val _ = blockDecoder(authProxies)
        Decoder.forProduct1(Attributes.acl)(NonEmptyList.fromListUnsafe[Block]).map(new SequentialAcl(_): Acl)
      }
    } yield acl
    decoder.accumulating.apply(c)
  }
}

object RorAclFactory {

  sealed trait AclCreationError

  object AclCreationError {

    final case class UnparsableYamlContent(value: String) extends AclCreationError

    final case class BlocksLevelCreationError(message: String) extends AclCreationError

    final case class RulesLevelCreationError(message: String) extends AclCreationError

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
