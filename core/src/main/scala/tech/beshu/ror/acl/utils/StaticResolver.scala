package tech.beshu.ror.acl.utils

object StaticResolver {

  def resolve(value: String): String = {
    (tryToResolveOldStyle _ andThen tryToResolve) apply value
  }

  private def tryToResolveOldStyle(value: String) = {
    if (value.startsWith(Variables.env)) value.substring(Variables.env.length)
    else if (value.startsWith(Variables.text)) value.substring(Variables.text.length)
    else value
  }

  private def tryToResolve(withVariable: String) = {
    Variables.regex
      .findAllMatchIn(withVariable)
      .foldLeft(withVariable) {
        case (currentValue, regexMatch) =>
          if (regexMatch.groupCount != 3) currentValue
          else {
            val variableType = regexMatch.group(2)
            val variableName = regexMatch.group(3)
            findVariable(variableType, variableName) match {
              case Some(value) =>
                val variableGroup = regexMatch.group(1)
                currentValue.replace(variableGroup, value)
              case None =>
                currentValue
            }
          }
      }
  }

  private def findVariable(`type`: String, name: String): Option[String] =
    `type` match {
      case "env" => Option(System.getenv(name))
      case _ => None
    }

  private object Variables {
    val regex = ".*(@\\{(.*):(.*)\\}).*".r

    val env = "env:"
    val text = "text:"
  }

}
