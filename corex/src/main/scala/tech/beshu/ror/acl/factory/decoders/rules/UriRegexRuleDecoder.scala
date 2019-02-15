package tech.beshu.ror.acl.factory.decoders.rules

import java.util.regex.Pattern

import cats.implicits._
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue._

import scala.util.Try
import tech.beshu.ror.acl.utils.CirceOps._

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
