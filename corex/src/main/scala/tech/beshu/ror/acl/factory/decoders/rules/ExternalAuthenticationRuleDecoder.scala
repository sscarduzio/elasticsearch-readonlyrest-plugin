package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService}
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule.Settings
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.{Definitions, ExternalAuthenticationServicesDecoder}
import tech.beshu.ror.acl.factory.decoders.definitions.ExternalAuthenticationServicesDecoder._
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.ExternalAuthenticationRuleDecoder._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration

class ExternalAuthenticationRuleDecoder(authenticationServices: Definitions[ExternalAuthenticationService])
  extends RuleDecoderWithoutAssociatedFields[ExternalAuthenticationRule](
    simpleExternalAuthenticationServiceNameAndLocalConfig
      .orElse(complexExternalAuthenticationServiceNameAndLocalConfig)
      .emapE {
        case (name, Some(ttl)) =>
          findAuthenticationService(authenticationServices.items, name)
            .map(new CachingExternalAuthenticationService(_, ttl))
        case (name, None) =>
          findAuthenticationService(authenticationServices.items, name)
      }
      .map(service => new ExternalAuthenticationRule(Settings(service)))
  )

object ExternalAuthenticationRuleDecoder {

  private def simpleExternalAuthenticationServiceNameAndLocalConfig: Decoder[(ExternalAuthenticationService.Name, Option[FiniteDuration Refined Positive])] =
    ExternalAuthenticationServicesDecoder
      .serviceNameDecoder
      .map((_, None))

  private def complexExternalAuthenticationServiceNameAndLocalConfig: Decoder[(ExternalAuthenticationService.Name, Option[FiniteDuration Refined Positive])] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("service").as[ExternalAuthenticationService.Name]
          ttl <- c.downField("cache_ttl_in_sec").as[FiniteDuration Refined Positive]
        } yield (name, Option(ttl))
      }
      .mapError(RulesLevelCreationError.apply)
  }

  private def findAuthenticationService(authenticationServices: Set[ExternalAuthenticationService],
                                        searchedServiceName: ExternalAuthenticationService.Name): Either[AclCreationError, ExternalAuthenticationService] = {
    authenticationServices.find(_.id === searchedServiceName) match {
      case Some(service) => Right(service)
      case None => Left(RulesLevelCreationError(Message(s"Cannot find external authentication service with name: ${searchedServiceName.show}")))
    }
  }
}