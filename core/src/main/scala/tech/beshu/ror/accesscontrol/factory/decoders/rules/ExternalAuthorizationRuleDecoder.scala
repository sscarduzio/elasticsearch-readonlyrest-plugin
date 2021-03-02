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

import cats.Order
import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthorizationServiceDecorator, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.ExternalAuthorizationServicesDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.CaseMappingEquality._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.FiniteDuration

class ExternalAuthorizationRuleDecoder(authotizationServices: Definitions[ExternalAuthorizationService],
                        implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleDecoderWithoutAssociatedFields[ExternalAuthorizationRule](
    ExternalAuthorizationRuleDecoder
      .settingsDecoder(authotizationServices, caseMappingEquality)
      .map(settings => RuleWithVariableUsageDefinition.create(new ExternalAuthorizationRule(settings, caseMappingEquality)))
  )

object ExternalAuthorizationRuleDecoder {

  private def settingsDecoder(authorizationServices: Definitions[ExternalAuthorizationService],
                              caseMappingEquality: UserIdCaseMappingEquality): Decoder[ExternalAuthorizationRule.Settings] = {
    implicit val orderUserId: Order[User.Id] = caseMappingEquality.toOrder
    Decoder
      .instance { c =>
        for {
          name <- c.downField("user_groups_provider").as[ExternalAuthorizationService.Name]
          groups <- c.downField("groups").as[UniqueNonEmptyList[Group]]
          users <- c.downField("users").as[Option[NonEmptySet[User.Id]]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groups, users.getOrElse(NonEmptySet.one(User.Id(NonEmptyString.unsafeFrom("*")))))
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, Some(ttl), groups, users) =>
          findAuthorizationService(authorizationServices.items, name)
            .map(new CacheableExternalAuthorizationServiceDecorator(_, ttl))
            .map(ExternalAuthorizationRule.Settings(_, groups, users))
        case (name, None, groups, users) =>
          findAuthorizationService(authorizationServices.items, name)
            .map(ExternalAuthorizationRule.Settings(_, groups, users))
      }
      .decoder
  }

  private def findAuthorizationService(authorizationServices: List[ExternalAuthorizationService],
                                       searchedServiceName: ExternalAuthorizationService.Name): Either[AclCreationError, ExternalAuthorizationService] = {
    authorizationServices.find(_.id === searchedServiceName) match {
      case Some(service) => Right(service)
      case None => Left(RulesLevelCreationError(Message(s"Cannot find user groups provider with name: ${searchedServiceName.show}")))
    }
  }
}