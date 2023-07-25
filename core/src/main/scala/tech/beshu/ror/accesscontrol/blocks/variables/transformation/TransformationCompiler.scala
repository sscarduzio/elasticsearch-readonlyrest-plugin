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
package tech.beshu.ror.accesscontrol.blocks.variables.transformation

import eu.timepit.refined.auto._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError.{UnableToCompileTransformation, UnableToParseTransformation}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.{Function, FunctionAlias, FunctionDefinition}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.parser.Parser

class TransformationCompiler(compiler: ExpressionCompiler, useAliases: Boolean) {

  def compile(transformationString: String): Either[CompilationError, domain.Function] = {
    for {
      expression <- Parser.parse(transformationString)
        .left.map(error => UnableToParseTransformation(error.message))
      function <- compiler.compile(expression, useAlias = useAliases)
        .left.map(error => UnableToCompileTransformation(error.message))
    } yield function
  }
}

object TransformationCompiler {
  sealed trait CompilationError
  object CompilationError {
    final case class UnableToParseTransformation(message: String) extends CompilationError
    final case class UnableToCompileTransformation(message: String) extends CompilationError
  }

  def withoutAliases: TransformationCompiler = {
    new TransformationCompiler(
      compiler = ExpressionCompiler.create(functions = supportedFunctions, aliases = List.empty),
      useAliases = false
    )
  }

  def withAliases(aliasedFunctions: Seq[FunctionAlias]): TransformationCompiler = {
    new TransformationCompiler(
      compiler = ExpressionCompiler.create(functions = supportedFunctions, aliases = aliasedFunctions),
      useAliases = true
    )
  }

  private val supportedFunctions: Seq[FunctionDefinition] = {
    Seq(
      FunctionDefinition[Function.ReplaceAll]("replace_all"),
      FunctionDefinition[Function.ReplaceFirst]("replace_first"),
      FunctionDefinition[Function.ToLowerCase]("to_lowercase"),
      FunctionDefinition[Function.ToUpperCase]("to_uppercase"),
    )
  }
}
