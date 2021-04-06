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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.data.NonEmptySet
import io.circe.CursorOp.DownField
import io.circe.Decoder.Result
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleWithVariableUsageDefinition}
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureOps

sealed abstract class RuleBaseDecoder[T <: Rule](val associatedFields: Set[String])
  extends Decoder[RuleWithVariableUsageDefinition[T]] {
  def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[RuleWithVariableUsageDefinition[T]] = {
    doDecode(value, associatedFieldsJson)
      .left
      .map(df => df.overrideDefaultErrorWith(RulesLevelCreationError {
        value.up.focus match {
          case Some(json) =>
            MalformedValue(json)
          case None =>
            val ruleName = df.history.headOption.collect { case df: DownField => df.k }.getOrElse("")
            Message(s"Malformed rule $ruleName")
        }
      }))
  }

  protected def doDecode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[RuleWithVariableUsageDefinition[T]]
}

sealed abstract class AuthenticationRuleBaseDecoder[T <: AuthenticationRule](val associatedFields: Set[String])
                                                                            (implicit eligibleUsers: EligibleUsers[T])
  extends Decoder[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] {

  def toRuleBaseDecoder: RuleBaseDecoder[T]

  def decode(value: ACursor, associatedFieldsJson: ACursor): Decoder.Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] = {
    doDecode(value, associatedFieldsJson)
      .left
      .map(df => df.overrideDefaultErrorWith(RulesLevelCreationError {
        value.up.focus match {
          case Some(json) =>
            MalformedValue(json)
          case None =>
            val ruleName = df.history.headOption.collect { case df: DownField => df.k }.getOrElse("")
            Message(s"Malformed rule $ruleName")
        }
      }))
  }

  protected def doDecode(value: ACursor,
                         associatedFieldsJson: ACursor): Decoder.Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)]
}

object RuleBaseDecoder {

  private[decoders] class AuthenticationRuleDecoderWithoutAssociatedFields[T <: AuthenticationRule](decoder: Decoder[RuleWithVariableUsageDefinition[T]])
                                                                                                   (implicit eligibleUsers: EligibleUsers[T])
    extends AuthenticationRuleBaseDecoder[T](Set.empty) {

    override protected def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] =
      decoder.tryDecode(value).map { r => (r, eligibleUsers.eligibleUsers(r.rule)) }

    override def apply(c: HCursor): Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] = {
      decoder.apply(c).map { r => (r, eligibleUsers.eligibleUsers(r.rule)) }
    }

    override def toRuleBaseDecoder: RuleBaseDecoder[T] = new RuleDecoderWithoutAssociatedFields(decoder)
  }

  private[decoders] class RuleDecoderWithoutAssociatedFields[T <: Rule](decoder: Decoder[RuleWithVariableUsageDefinition[T]])
    extends RuleBaseDecoder[T](Set.empty) {
    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[RuleWithVariableUsageDefinition[T]] =
      decoder.tryDecode(value)

    override def apply(c: HCursor): Result[RuleWithVariableUsageDefinition[T]] =
      decoder.apply(c)
  }

  private[decoders] class AuthenticationRuleDecoderWithAssociatedFields[T <: AuthenticationRule, S](ruleDecoderCreator: S => Decoder[RuleWithVariableUsageDefinition[T]],
                                                                                                    associatedFields: NonEmptySet[String],
                                                                                                    associatedFieldsDecoder: Decoder[S])
                                                                                                   (implicit eligibleUsers: EligibleUsers[T])
    extends AuthenticationRuleBaseDecoder[T](associatedFields.toSortedSet) {

    override protected def doDecode(value: ACursor,
                                    associatedFieldsJson: ACursor): Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] = {
      for {
        decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
        rule <- ruleDecoderCreator(decodedAssociatedFields)
          .tryDecode(value)
          .map { r => (r, eligibleUsers.eligibleUsers(r.rule)) }
      } yield rule
    }

    override def apply(c: HCursor): Result[(RuleWithVariableUsageDefinition[T], EligibleUsers.Support)] =
      Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message("Rule with associated fields decoding failed"))))

    override def toRuleBaseDecoder: RuleBaseDecoder[T] =
      new RuleDecoderWithAssociatedFields(ruleDecoderCreator, associatedFields, associatedFieldsDecoder)
  }

  private[decoders] class RuleDecoderWithAssociatedFields[T <: Rule, S](ruleDecoderCreator: S => Decoder[RuleWithVariableUsageDefinition[T]],
                                                                        associatedFields: NonEmptySet[String],
                                                                        associatedFieldsDecoder: Decoder[S])
    extends RuleBaseDecoder[T](associatedFields.toSortedSet) {
    override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[RuleWithVariableUsageDefinition[T]] = {
      for {
        decodedAssociatedFields <- associatedFieldsDecoder.tryDecode(associatedFieldsJson)
        rule <- ruleDecoderCreator(decodedAssociatedFields).tryDecode(value)
      } yield rule
    }

    override def apply(c: HCursor): Result[RuleWithVariableUsageDefinition[T]] =
      Left(DecodingFailureOps.fromError(RulesLevelCreationError(Message("Rule with associated fields decoding failed"))))
  }

  private[decoders] def failed[T <: Rule](error: RulesLevelCreationError): RuleBaseDecoder[T] =
    new RuleBaseDecoder[T](Set.empty) {
      private val decodingFailureResult = Left(DecodingFailureOps.fromError(error))

      override def doDecode(value: ACursor, associatedFieldsJson: ACursor): Result[RuleWithVariableUsageDefinition[T]] = decodingFailureResult

      override def apply(c: HCursor): Result[RuleWithVariableUsageDefinition[T]] = decodingFailureResult
    }
}

trait EligibleUsers[T <: AuthenticationRule] {
  def eligibleUsers(rule: T): EligibleUsers.Support
}
object EligibleUsers {
  sealed trait Support
  object Support {
    final case class Available(users: Set[User.Id]) extends Support
    case object NotAvailable extends Support
  }

  def notSupported[T <: AuthenticationRule]: EligibleUsers[T] = new EligibleUsers[T] {
    override def eligibleUsers(rule: T): EligibleUsers.Support = EligibleUsers.Support.NotAvailable
  }

  def supported[T <: AuthenticationRule](usersFromRule: T => Set[User.Id]): EligibleUsers[T] = new EligibleUsers[T] {
    override def eligibleUsers(rule: T): EligibleUsers.Support = EligibleUsers.Support.Available(usersFromRule(rule))
  }

  object Instances {

    implicit val authKeyRuleEligibleUsers: EligibleUsers[AuthKeyRule] =
      supported[AuthKeyRule](r => Set(r.settings.credentials.user))

    implicit val authKeySha1RuleEligibleUsers: EligibleUsers[AuthKeySha1Rule] = new EligibleUsers[AuthKeySha1Rule] {
      override def eligibleUsers(rule: AuthKeySha1Rule): Support = rule.settings.credentials match {
        case HashedCredentials.HashedUserAndPassword(_) =>  Support.NotAvailable
        case HashedCredentials.HashedOnlyPassword(userId, _) => Support.Available(Set(userId))
      }
    }

    implicit val authKeySha256RuleEligibleUsers: EligibleUsers[AuthKeySha256Rule] = new EligibleUsers[AuthKeySha256Rule] {
      override def eligibleUsers(rule: AuthKeySha256Rule): Support = rule.settings.credentials match {
        case HashedCredentials.HashedUserAndPassword(_) =>  Support.NotAvailable
        case HashedCredentials.HashedOnlyPassword(userId, _) => Support.Available(Set(userId))
      }
    }

    implicit val authKeySha512RuleEligibleUsers: EligibleUsers[AuthKeySha512Rule] = new EligibleUsers[AuthKeySha512Rule] {
      override def eligibleUsers(rule: AuthKeySha512Rule): Support = rule.settings.credentials match {
        case HashedCredentials.HashedUserAndPassword(_) =>  Support.NotAvailable
        case HashedCredentials.HashedOnlyPassword(userId, _) => Support.Available(Set(userId))
      }
    }

    implicit val authKeyUnixRuleEligibleUsers: EligibleUsers[AuthKeyUnixRule] =
      supported[AuthKeyUnixRule](r => Set(r.settings.credentials.userId))

    implicit val authKeyPBKDF2WithHmacSHA512RuleEligibleUsers: EligibleUsers[AuthKeyPBKDF2WithHmacSHA512Rule] = new EligibleUsers[AuthKeyPBKDF2WithHmacSHA512Rule] {
      override def eligibleUsers(rule: AuthKeyPBKDF2WithHmacSHA512Rule): Support = rule.settings.credentials match {
        case HashedCredentials.HashedUserAndPassword(_) =>  Support.NotAvailable
        case HashedCredentials.HashedOnlyPassword(userId, _) => Support.Available(Set(userId))
      }
    }

    implicit val externalAuthenticationRuleEligibleUsers: EligibleUsers[ExternalAuthenticationRule] = notSupported

    implicit val jwtAuthRuleEligibleUsers: EligibleUsers[JwtAuthRule] = notSupported

    implicit val ldapAuthRuleEligibleUsers: EligibleUsers[LdapAuthRule] = notSupported

    implicit val ldapAuthenticationRuleEligibleUsers: EligibleUsers[LdapAuthenticationRule] = notSupported

    implicit val proxyAuthRuleEligibleUsers: EligibleUsers[ProxyAuthRule] = notSupported

    implicit val rorKbnAuthRuleEligibleUsers: EligibleUsers[RorKbnAuthRule] = notSupported

  }
}
