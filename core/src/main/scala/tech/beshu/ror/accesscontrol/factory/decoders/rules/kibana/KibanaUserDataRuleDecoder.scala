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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.kibana

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaUserDataRule
import tech.beshu.ror.accesscontrol.blocks.variables.VariableCreationConfig
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Json.ResolvableJsonRepresentation
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath.AllowedHttpMethod.HttpMethod
import tech.beshu.ror.accesscontrol.domain.KibanaAllowedApiPath._
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaAccess, KibanaAllowedApiPath, KibanaApp, KibanaIndexName, Regex, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{RulesLevelCreationError, ValueLevelCreationError}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._

import scala.util.{Failure, Success}

class KibanaUserDataRuleDecoder(configurationIndex: RorConfigurationIndex,
                                variableCreationConfig: VariableCreationConfig)
  extends RuleBaseDecoderWithoutAssociatedFields[KibanaUserDataRule]
    with Logging {

  private implicit val _variableCreationConfig: VariableCreationConfig = variableCreationConfig
  private implicit val uniqueNonEmptyListOfKibanaAppsDecoder: Decoder[Set[KibanaApp]] =
    DecoderHelpers.decodeStringLikeOrSet[KibanaApp]

  override protected def decoder: Decoder[Block.RuleDefinition[KibanaUserDataRule]] = {
    Decoder
      .instance { c =>
        for {
          access <- c.downField("access").as[KibanaAccess]
          kibanaIndex <- c.downField("index").as[Option[RuntimeSingleResolvableVariable[KibanaIndexName]]]
          kibanaTemplateIndex <- c.downField("template_index").as[Option[RuntimeSingleResolvableVariable[KibanaIndexName]]]
          appsToHide <- c.downField("hide_apps").as[Option[Set[KibanaApp]]]
          allowedApiPaths <- c.downField("allowed_api_paths").as[Option[Set[KibanaAllowedApiPath]]]
          metadataResolvableJsonRepresentation <- c.keys.flatMap(_.find(_ == "metadata")) match {
            case Some(_) => c.downField("metadata").as[ResolvableJsonRepresentation].map(Some.apply)
            case None => Right(None)
          }
        } yield new KibanaUserDataRule(KibanaUserDataRule.Settings(
          access = access,
          kibanaIndex = kibanaIndex.getOrElse(
            RuntimeSingleResolvableVariable.AlreadyResolved(ClusterIndexName.Local.kibanaDefault
            )),
          kibanaTemplateIndex = kibanaTemplateIndex,
          appsToHide = appsToHide.getOrElse(Set.empty),
          allowedApiPaths = allowedApiPaths.getOrElse(Set.empty),
          metadata = metadataResolvableJsonRepresentation,
          rorIndex = configurationIndex
        ))
      }
      .map(RuleDefinition.create[KibanaUserDataRule](_))
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }

  private implicit lazy val kibanaAllowedApiPathLikeDecoder: Decoder[KibanaAllowedApiPath] = {
    implicit val simpleKibanaAllowedApiPathDecoder: Decoder[KibanaAllowedApiPath] =
      pathRegexDecoder.map(KibanaAllowedApiPath(AllowedHttpMethod.Any, _))

    val extendedKibanaAllowedApiDecoder: Decoder[KibanaAllowedApiPath] = Decoder.instance { c =>
      for {
        httpMethod <- c.downField("http_method").as[HttpMethod]
        httpPath <- c.downField("http_path").as[Regex]
      } yield KibanaAllowedApiPath(AllowedHttpMethod.Specific(httpMethod), httpPath)
    }

    extendedKibanaAllowedApiDecoder.or(simpleKibanaAllowedApiPathDecoder)
  }

  private implicit lazy val pathRegexDecoder: Decoder[Regex] =
    Decoder
      .decodeString
      .toSyncDecoder
      .emapE { str =>
        NonEmptyString
          .unapply(str)
          .toRight(ValueLevelCreationError(Message(s"Cannot create kibana allowed API from an empty string")))
      }
      .emapE(pathRegexFrom)
      .decoder

  private implicit lazy val httpMethodDecoder: Decoder[HttpMethod] =
    Decoder
      .decodeString
      .map(_.toUpperCase())
      .toSyncDecoder
      .emapE[HttpMethod] {
        case "GET" => Right(HttpMethod.Get)
        case "POST" => Right(HttpMethod.Post)
        case "PUT" => Right(HttpMethod.Put)
        case "DELETE" => Right(HttpMethod.Delete)
        case unknown => Left(CoreCreationError.ValueLevelCreationError(Message(
          s"Unsupported HTTP method: '$unknown'. Available options: 'GET', 'POST', 'PUT', 'DELETE'"
        )))
      }
      .decoder

  private def pathRegexFrom(str: NonEmptyString) = {
    if (str.value.startsWith("^") && str.value.endsWith("$")) {
      Regex.compile(str.value) match {
        case Success(regex) =>
          Right(regex)
        case Failure(exception) =>
          logger.error(s"Cannot compile regex from string: [$str]", exception)
          Left(ValueLevelCreationError(Message(s"Cannot create Kibana allowed API path regex from [$str]")))
      }
    } else {
      Right(Regex.buildFromLiteral(str.value))
    }
  }
}
