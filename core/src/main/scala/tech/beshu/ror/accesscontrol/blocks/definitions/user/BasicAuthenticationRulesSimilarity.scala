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
package tech.beshu.ror.accesscontrol.blocks.definitions.user

import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials.{HashedOnlyPassword, HashedUserAndPassword}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.utils.Similarity
import tech.beshu.ror.utils.Similarity.SimilarityMeasure

private[user] object BasicAuthenticationRulesSimilarity {

  private type SupportedRule = AuthKeyRule | AuthKeyUnixRule | AuthKeyHashingRule

  def similarity(x: BasicAuthenticationRule[?], y: BasicAuthenticationRule[?]): SimilarityMeasure = {
    (for {
      supportedRuleX <- toSupportedRule(x)
      supportedRuleY <- toSupportedRule(y)
    } yield Similarity(supportedRuleX, supportedRuleY)).getOrElse(SimilarityMeasure.Unrelated)
  }

  private def toSupportedRule(basicAuthenticationRule: BasicAuthenticationRule[?]): Option[SupportedRule] =
    basicAuthenticationRule match {
      case rule: AuthKeyHashingRule => Some(rule)
      case rule: AuthKeyRule => Some(rule)
      case rule: AuthKeyUnixRule => Some(rule)
      case _ => None
    }

  private def equalityCheck[A](x: A, y: A): SimilarityMeasure = if (x == y) SimilarityMeasure.RelatedAndEqual else SimilarityMeasure.RelatedAndNotEqual

  given Similarity[HashedCredentials] = (x: HashedCredentials, y: HashedCredentials) => (x, y) match {
    case (c1: HashedUserAndPassword, c2: HashedUserAndPassword) => equalityCheck(c1, c2)
    case (c1: HashedOnlyPassword, c2: HashedOnlyPassword) => equalityCheck(c1, c2)
    case (_: HashedUserAndPassword | _: HashedOnlyPassword, _) => SimilarityMeasure.Unrelated
  }

  given Similarity[AuthKeyRule] = (x: AuthKeyRule, y: AuthKeyRule) =>
    equalityCheck(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeyUnixRule] = (x: AuthKeyUnixRule, y: AuthKeyUnixRule) =>
    equalityCheck(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeySha1Rule] = (x: AuthKeySha1Rule, y: AuthKeySha1Rule) =>
    Similarity(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeySha256Rule] = (x: AuthKeySha256Rule, y: AuthKeySha256Rule) =>
    Similarity(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeySha512Rule] = (x: AuthKeySha512Rule, y: AuthKeySha512Rule) =>
    Similarity(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeyPBKDF2WithHmacSHA512Rule] = (x: AuthKeyPBKDF2WithHmacSHA512Rule, y: AuthKeyPBKDF2WithHmacSHA512Rule) =>
    Similarity(x.settings.credentials, y.settings.credentials)

  given Similarity[AuthKeyHashingRule] = (x: AuthKeyHashingRule, y: AuthKeyHashingRule) => {
    (x, y) match {
      case (r1: AuthKeySha1Rule, r2: AuthKeySha1Rule) => Similarity(r1, r2)
      case (r1: AuthKeySha256Rule, r2: AuthKeySha256Rule) => Similarity(r1, r2)
      case (r1: AuthKeySha512Rule, r2: AuthKeySha512Rule) => Similarity(r1, r2)
      case (r1: AuthKeyPBKDF2WithHmacSHA512Rule, r2: AuthKeyPBKDF2WithHmacSHA512Rule) => Similarity(r1, r2)
      case (_: AuthKeySha1Rule | _: AuthKeySha256Rule | _: AuthKeySha512Rule | _: AuthKeyPBKDF2WithHmacSHA512Rule, _) => SimilarityMeasure.Unrelated
    }
  }

  given Similarity[SupportedRule] = (x: SupportedRule, y: SupportedRule) => (x, y) match {
    case (r1: AuthKeyRule, r2: AuthKeyRule) => Similarity(r1, r2)
    case (r1: AuthKeyUnixRule, r2: AuthKeyUnixRule) => Similarity(r1, r2)
    case (r1: AuthKeyHashingRule, r2: AuthKeyHashingRule) => Similarity(r1, r2)
    case (_: AuthKeyRule | _: AuthKeyUnixRule | _: AuthKeyHashingRule, _) => SimilarityMeasure.Unrelated
  }

}
