package tech.beshu.ror.acl.factory.decoders

import java.time.Clock

import eu.timepit.refined._
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.acl.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.acl.factory.decoders.SessionMaxIdleRuleDecoderHelper.convertStringToFiniteDuration
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.UuidProvider
import tech.beshu.ror.commons.refined._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

class SessionMaxIdleRuleDecoder(implicit clock: Clock, uuidProvider: UuidProvider)
  extends RuleDecoderWithoutAssociatedFields(
    Decoder
      .decodeString
      .emap(convertStringToFiniteDuration)
      .emap(refineV[Positive](_))
      .map(maxIdle => new SessionMaxIdleRule(Settings(maxIdle)))
  )

private object SessionMaxIdleRuleDecoderHelper {
  def convertStringToFiniteDuration(value: String): Either[String, FiniteDuration] = {
    Try(Duration(value)) match {
      case Success(v: FiniteDuration) => Right(v)
      case Success(_) | Failure(_) => Left(s"Cannot convert value $value to duration")
    }
  }
}