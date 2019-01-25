package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthorizationService, ExternalAuthorizationService}
import tech.beshu.ror.acl.blocks.rules.ExternalAuthorizationRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.definitions.ExternalAuthorizationServicesDecoder._
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration

class ExternalAuthorizationRuleDecoder(authotizationServices: Definitions[ExternalAuthorizationService])
  extends RuleDecoderWithoutAssociatedFields[ExternalAuthorizationRule](
    ExternalAuthorizationRuleDecoder
      .settingsDecoder(authotizationServices)
      .map(new ExternalAuthorizationRule(_))
  )

object ExternalAuthorizationRuleDecoder {

  private def settingsDecoder(authorizationServices: Definitions[ExternalAuthorizationService]): Decoder[ExternalAuthorizationRule.Settings] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("user_groups_provider").as[ExternalAuthorizationService.Name]
          groups <- c.downField("groups").as[NonEmptySet[Group]]
          users <- c.downField("users").as[Option[NonEmptySet[User.Id]]]
          ttl <- c.downField("cache_ttl_in_sec").as[FiniteDuration Refined Positive]
        } yield (name, Option(ttl), groups, users.getOrElse(NonEmptySet.one(User.Id("*"))))
      }
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, Some(ttl), groups, users) =>
          findAuthorizationService(authorizationServices.items, name)
            .map(new CachingExternalAuthorizationService(_, ttl))
            .map(ExternalAuthorizationRule.Settings(_, groups, users))
        case (name, None, groups, users) =>
          findAuthorizationService(authorizationServices.items, name)
            .map(ExternalAuthorizationRule.Settings(_, groups, users))
      }
  }

  private def findAuthorizationService(authorizationServices: Set[ExternalAuthorizationService],
                                       searchedServiceName: ExternalAuthorizationService.Name): Either[AclCreationError, ExternalAuthorizationService] = {
    authorizationServices.find(_.id === searchedServiceName) match {
      case Some(service) => Right(service)
      case None => Left(RulesLevelCreationError(Message(s"Cannot find user groups provider with name: ${searchedServiceName.show}")))
    }
  }
}