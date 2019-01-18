package tech.beshu.ror.acl.factory.decoders.rules

import java.time.Clock

import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.SessionMaxIdleRuleDecoderHelper.convertStringToFiniteDuration
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.UuidProvider
import tech.beshu.ror.acl.refined._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

class SessionMaxIdleRuleDecoder(implicit clock: Clock, uuidProvider: UuidProvider)
  extends RuleDecoderWithoutAssociatedFields(
    Decoder
      .decodeString
      .emapE(convertStringToFiniteDuration)
      .emapE { value =>
        refineV[Positive](value)
          .left
          .map(_ => RulesLevelCreationError(Message(s"Only positive durations allowed. Found: ${value.toString()}")))
      }
      .map(maxIdle => new SessionMaxIdleRule(Settings(maxIdle)))
  )

private object SessionMaxIdleRuleDecoderHelper {
  def convertStringToFiniteDuration(value: String): Either[RulesLevelCreationError, FiniteDuration] = {
    Try(Duration(value)) match {
      case Success(v: FiniteDuration) => Right(v)
      case Success(_) | Failure(_) => Left(RulesLevelCreationError(Message(s"Cannot convert value '$value' to duration")))
    }
  }
}