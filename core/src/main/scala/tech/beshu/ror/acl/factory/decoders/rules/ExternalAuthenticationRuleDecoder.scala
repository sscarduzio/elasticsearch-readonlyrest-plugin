package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.{CachingExternalAuthenticationService, ExternalAuthenticationService, ExternalAuthenticationServicesDefinitions}
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.acl.blocks.rules.ExternalAuthenticationRule.Settings
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.ExternalAuthenticationServicesDecoder
import tech.beshu.ror.acl.factory.decoders.definitions.ExternalAuthenticationServicesDecoder._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.ExternalAuthenticationRuleDecoder._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration

class ExternalAuthenticationRuleDecoder(authenticationServices: ExternalAuthenticationServicesDefinitions)
  extends RuleDecoderWithoutAssociatedFields[ExternalAuthenticationRule](
    simpleExternalAuthenticationRuleDecoder(authenticationServices) orElse complexExternalAuthenticationRuleDecoder(authenticationServices)
  )

object ExternalAuthenticationRuleDecoder {
  private def simpleExternalAuthenticationRuleDecoder(authenticationServices: ExternalAuthenticationServicesDefinitions): Decoder[ExternalAuthenticationRule] =
    ExternalAuthenticationServicesDecoder
      .serviceNameDecoder
      .emapE { name => findAuthenticationService(authenticationServices.services, name) }
      .map(service => new ExternalAuthenticationRule(Settings(service)))

  private def complexExternalAuthenticationRuleDecoder(authenticationServices: ExternalAuthenticationServicesDefinitions): Decoder[ExternalAuthenticationRule] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("service").as[ExternalAuthenticationService.Name]
          ttl <- c.downField("cache_ttl_in_sec").as[FiniteDuration Refined Positive]
          service <- findAuthenticationService(authenticationServices.services, name)
            .map(new CachingExternalAuthenticationService(_, ttl))
            .left.map(DecodingFailureOps.fromError)
        } yield service: ExternalAuthenticationService
      }
      .map(service => new ExternalAuthenticationRule(Settings(service)))
      .mapError(RulesLevelCreationError.apply)
  }

  private def findAuthenticationService(authenticationServices: Set[ExternalAuthenticationService],
                                        searchedServiceName: ExternalAuthenticationService.Name) = {
    authenticationServices.find(_.name === searchedServiceName) match {
      case Some(service) => Right(service)
      case None => Left(RulesLevelCreationError(Message(s"Cannot find external authentication service with name: ${searchedServiceName.show}")))
    }
  }
}

//external_authentication