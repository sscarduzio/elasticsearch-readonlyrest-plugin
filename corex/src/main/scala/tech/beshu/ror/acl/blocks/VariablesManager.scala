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