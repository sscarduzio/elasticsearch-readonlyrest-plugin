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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.RepositoriesRule
import tech.beshu.ror.accesscontrol.blocks.variables.VariableCreationConfig
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch.RepositoriesDecodersHelper._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}

class RepositoriesRuleDecoder(variableCreationConfig: VariableCreationConfig)
  extends RuleBaseDecoderWithoutAssociatedFields[RepositoriesRule] {

  private implicit val _variableCreationConfig: VariableCreationConfig = variableCreationConfig

  override protected def decoder: Decoder[RuleDefinition[RepositoriesRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]]
      .toSyncDecoder
      .emapE { repositories =>
        if (checkIfAlreadyResolvedRepositoryVariableContains(repositories, RepositoryName.all))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.Name.show}) that matches all the values is redundant - repository ${RepositoryName.all.show}")))
        else if (checkIfAlreadyResolvedRepositoryVariableContains(repositories, RepositoryName.wildcard))
          Left(RulesLevelCreationError(Message(s"Setting up a rule (${RepositoriesRule.Name.show}) that matches all the values is redundant - repository ${RepositoryName.wildcard.show}")))
        else
          Right(repositories)
      }
      .map(repositories => RuleDefinition.create(new RepositoriesRule(RepositoriesRule.Settings(repositories))))
      .decoder
  }
}

private object RepositoriesDecodersHelper {
  private implicit val indexNameConvertible: Convertible[RepositoryName] = new Convertible[RepositoryName] {
    override def convert: String => Either[Convertible.ConvertError, RepositoryName] = str =>
      RepositoryName.from(str) match {
        case Some(value) => Right(value)
        case None => Left(Convertible.ConvertError("Repository name cannot be empty"))
      }
  }
  implicit def repositoryValueDecoder(implicit variableCreationConfig: VariableCreationConfig): Decoder[RuntimeMultiResolvableVariable[RepositoryName]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        RuntimeResolvableVariableCreator
          .createMultiResolvableVariableFrom[RepositoryName](str)
          .left.map(error => RulesLevelCreationError(Message(error.show)))
      }
      .decoder

  private[rules] def checkIfAlreadyResolvedRepositoryVariableContains(repositoriesVars: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]],
                                                                      repository: RepositoryName): Boolean = {
    repositoriesVars
      .find {
        case AlreadyResolved(repositories) => repositories.contains_(repository)
        case ToBeResolved(_) => false
      }
      .isDefined
  }
}
