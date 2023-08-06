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

import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError.{UnableToCompileTransformation, UnableToParseTransformation}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.FunctionAlias
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

  def withoutAliases(supportedFunctions: SupportedVariablesFunctions): TransformationCompiler = {
    new TransformationCompiler(
      compiler = ExpressionCompiler.create(functions = supportedFunctions.functions, aliases = List.empty),
      useAliases = false
    )
  }

  def withAliases(supportedFunctions: SupportedVariablesFunctions,
                  aliasedFunctions: Seq[FunctionAlias]): TransformationCompiler = {
    new TransformationCompiler(
      compiler = ExpressionCompiler.create(functions = supportedFunctions.functions, aliases = aliasedFunctions),
      useAliases = true
    )
  }
}
