package tech.beshu.ror.unit.acl.factory.decoders

import java.util.regex.Pattern
import cats.implicits._
import tech.beshu.ror.unit.acl.blocks.Value
import tech.beshu.ror.unit.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.unit.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.unit.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.unit.acl.utils.CirceOps._

import scala.util.Try

object UriRegexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .valueDecoder { rv =>
      Try(Pattern.compile(rv.value))
        .toEither
        .left
        .map(_ => Value.ConvertError(rv, "Cannot compile pattern"))
    }
    .emapE {
      case Right(pattern) => Right(new UriRegexRule(Settings(pattern)))
      case Left(error) => Left(RulesLevelCreationError(Message(s"${error.msg}: ${error.resolvedValue.show}")))
    }
)
