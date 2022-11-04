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
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthorizationServiceDecorator, ExternalAuthorizationService, ImpersonatorDef}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{PermittedGroups, User}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.ExternalAuthorizationServicesDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.CaseMappingEquality._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.FiniteDuration

class ExternalAuthorizationRuleDecoder(authorizationServices: Definitions[ExternalAuthorizationService],
                                       impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                       mocksProvider: MocksProvider,
                                       implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[ExternalAuthorizationRule] {

  override protected def decoder: Decoder[RuleDefinition[ExternalAuthorizationRule]] = {
    settingsDecoder(authorizationServices, caseMappingEquality)
      .map(settings => RuleDefinition.create(
        new ExternalAuthorizationRule(settings, impersonatorsDef.toImpersonation(mocksProvider), caseMappingEquality)
      ))
  }

  private def settingsDecoder(authorizationServices: Definitions[ExternalAuthorizationService],
                              caseMappingEquality: UserIdCaseMappingEquality): Decoder[ExternalAuthorizationRule.Settings] = {
    implicit val orderUserId: Order[User.Id] = caseMappingEquality.toOrder
    Decoder
      .instance { c =>
        for {
          name <- c.downField("user_groups_provider").as[ExternalAuthorizationService.Name]
          permittedGroups <- c.downField("groups").as[PermittedGroups]
          users <- c.downField("users").as[Option[UniqueNonEmptyList[User.Id]]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, permittedGroups, users.getOrElse(UniqueNonEmptyList.of(User.Id(NonEmptyString.unsafeFrom("*")))))
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
                                       searchedServiceName: ExternalAuthorizationService.Name): Either[CoreCreationError, ExternalAuthorizationService] = {
    authorizationServices.find(_.id === searchedServiceName) match {
      case Some(service) => Right(service)
      case None => Left(RulesLevelCreationError(Message(s"Cannot find user groups provider with name: ${searchedServiceName.show}")))
    }
  }
}
