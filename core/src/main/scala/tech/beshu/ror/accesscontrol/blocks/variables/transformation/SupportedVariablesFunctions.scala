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
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.{Function, FunctionDefinition}

final case class SupportedVariablesFunctions(functions: Seq[FunctionDefinition])

object SupportedVariablesFunctions {
  val default: SupportedVariablesFunctions = SupportedVariablesFunctions(
    Seq(
      FunctionDefinition[Function.ReplaceAll]("replace_all"),
      FunctionDefinition[Function.ReplaceFirst]("replace_first"),
      FunctionDefinition[Function.ToLowerCase]("to_lowercase"),
      FunctionDefinition[Function.ToUpperCase]("to_uppercase"),
    )
  )
}
