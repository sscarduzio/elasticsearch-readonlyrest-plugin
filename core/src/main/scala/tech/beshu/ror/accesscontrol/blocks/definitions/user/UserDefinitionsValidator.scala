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

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SeparateRules, SingleRule}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.utils.Similarity.SimilarityMeasure
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

object UserDefinitionsValidator {

  sealed trait ValidationError

  object ValidationError {
    final case class MultipleUserEntriesWithDifferentCredentials(user: UserIdPattern,
                                                                 ruleNames: UniqueNonEmptyList[Rule.Name]) extends ValidationError
  }

  def validate(definitions: Definitions[UserDef]): ValidatedNel[ValidationError, Unit] = {
    val localUsersWithBasicAuthenticationRule: List[(UserIdPattern, List[BasicAuthenticationRule[?]])] =
      definitions.items
        .flatMap { definition =>
          (for {
            localUsers <- UniqueNonEmptyList.fromSortedSet(definition.usernames.patterns.filterNot(_.containsWildcard))
            basicAuthenticationRules <- basicAuthenticationRuleFrom(definition.mode)
          } yield localUsers.toList.map(user => (user, basicAuthenticationRules))).getOrElse(List.empty)
        }
        .groupBy {
          case (userIdPattern, _) => userIdPattern
        }
        .view
        .mapValues {
          _.map {
            case (_, basicAuthenticationRules) => basicAuthenticationRules
          }
        }
        .toList

    val validationErrors =
      localUsersWithBasicAuthenticationRule
        .flatMap {
          case (localUser, authenticationRules) =>
            UniqueNonEmptyList
              .fromIterable(findRulesWithNotMatchingCredentials(authenticationRules))
              .map(ruleNames => ValidationError.MultipleUserEntriesWithDifferentCredentials(localUser, ruleNames))
        }

    NonEmptyList.fromList(validationErrors) match {
      case Some(errors) => Validated.Invalid(errors)
      case None => Validated.Valid(())
    }
  }

  private def basicAuthenticationRuleFrom(mode: UserDef.Mode): Option[BasicAuthenticationRule[?]] = mode match {
    case Mode.WithoutGroupsMapping(authenticationRule: BasicAuthenticationRule[?], _) => Some(authenticationRule)
    case Mode.WithoutGroupsMapping(_, _) => None
    case Mode.WithGroupsMapping(SeparateRules(authenticationRule: BasicAuthenticationRule[?], _), _) => Some(authenticationRule)
    case Mode.WithGroupsMapping(SeparateRules(_, _), _) => None
    case Mode.WithGroupsMapping(SingleRule(_: AuthRule), _) => None
  }

  private def findRulesWithNotMatchingCredentials(authenticationRules: List[BasicAuthenticationRule[?]]) = {
    authenticationRules.combinations(2).toList.flatMap {
      case rule1 :: rule2 :: Nil =>
        BasicAuthenticationRulesSimilarity.similarity(rule1, rule2) match {
          case SimilarityMeasure.Unrelated => List.empty
          case SimilarityMeasure.RelatedAndEqual => List.empty // entries with the same credentials
          case SimilarityMeasure.RelatedAndNotEqual =>
            List(rule1.name)
        }
      case _ => // will not happen for combinations(2)
        List.empty
    }
  }

}
