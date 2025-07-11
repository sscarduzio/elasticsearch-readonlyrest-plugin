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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import cats.Id
import cats.data.NonEmptyMap
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.VariableTransformationAliasDef
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.{FunctionAlias, FunctionName}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, Reason}
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureOps
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.implicits.*

object VariableTransformationAliasesDefinitionsDecoder {

  private val definitionsSectionName = "variables_function_aliases"

  def create(supportedFunctions: SupportedVariablesFunctions): ADecoder[Id, Definitions[VariableTransformationAliasDef]] = {
    val transformationCompiler = TransformationCompiler.withoutAliases(supportedFunctions)
    implicit val decoder: SyncDecoder[VariableTransformationAliasDef] =
      SyncDecoderCreator.from(aliasesDefinitionsDecoder(transformationCompiler))
    DefinitionsBaseDecoder
      .instance[Id, VariableTransformationAliasDef](definitionsSectionName)
  }

  private def aliasesDefinitionsDecoder(transformationCompiler: TransformationCompiler): Decoder[VariableTransformationAliasDef] = {
    Decoder
      .instance { c =>
        for {
          keyAndValue <- c.as[NonEmptyMap[String, String]].map(_.toNel).map(_.head)
          aliasName <- NonEmptyString.from(keyAndValue._1).left.map(_ => error("Alias name cannot be empty"))
          function <- transformationCompiler.compile(keyAndValue._2).left.map {
            case CompilationError.UnableToParseTransformation(message) =>
              error(s"Unable to parse transformation for alias '${aliasName.show}'. Cause: ${message.show}")
            case CompilationError.UnableToCompileTransformation(message) =>
              error(s"Unable to compile transformation for alias '${aliasName.show}'. Cause: ${message.show}")
          }
        } yield VariableTransformationAliasDef(
          FunctionAlias(
            name = FunctionName(aliasName),
            value = function
          )
        )
      }
  }

  private def error(message: String) = {
    val errorMessage = s"${definitionsSectionName.show} definition malformed: ${message.show}"
    DecodingFailureOps.fromError(DefinitionsLevelCreationError(Reason.Message(errorMessage)))
  }
}
