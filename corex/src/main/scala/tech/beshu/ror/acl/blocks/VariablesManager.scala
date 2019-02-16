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
package tech.beshu.ror.acl.blocks

import tech.beshu.ror.TemplateReplacer
import tech.beshu.ror.acl.blocks.Variable.{ResolvedValue, ValueWithVariable}
import tech.beshu.ror.acl.request.RequestContext

import scala.collection.JavaConverters._

class VariablesManager(requestContext: RequestContext)
  extends VariablesResolver {

  private val templateReplacer = new TemplateReplacer(
    VariablesManager.escapeChar, VariablesManager.delimiterBeginChar, VariablesManager.delimiterEndChar
  )
  private val rawHeadersMap = requestContext
    .headers
    .map(h => (h.name.value.value.toLowerCase, h.value.value))
    .toMap

  override def resolve(value: ValueWithVariable, blockContext: BlockContext): Option[ResolvedValue] = {
    val potentiallyResolved = tryToReplaceVariableWithUser(value, blockContext) match {
      case None =>
        tryToReplaceVariableWithHeaders(value)
      case Some(replaced) if Variable.isStringVariable(replaced) =>
        tryToReplaceVariableWithHeaders(ValueWithVariable(replaced))
      case Some(replaced) =>
        replaced
    }
    if(Variable.isStringVariable(potentiallyResolved)) None
    else Some(ResolvedValue(potentiallyResolved))
  }

  private def tryToReplaceVariableWithUser(variable: ValueWithVariable,
                                           blockContext: BlockContext): Option[String] = {
    blockContext
      .loggedUser
      .map(user =>
        templateReplacer.replace(Map("user" -> user.id.value).asJava, variable.raw)
      )
  }

  private def tryToReplaceVariableWithHeaders(variable: ValueWithVariable) = {
    templateReplacer.replace(rawHeadersMap.asJava, variable.raw)
  }


}

object VariablesManager {
  private val escapeChar = '@'
  private val delimiterBeginChar = '{'
  private val delimiterEndChar = '}'
  val varDetector: String = String.valueOf(escapeChar) + delimiterBeginChar
}