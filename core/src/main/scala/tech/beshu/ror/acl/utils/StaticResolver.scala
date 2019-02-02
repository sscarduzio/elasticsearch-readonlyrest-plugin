package tech.beshu.ror.acl.utils

import scala.util.Try

object StaticResolver {

  def resolve(value: String): Option[String] = {
    tryToResolveOldStyle(value).orElse(tryToResolve(value))
  }

  private def tryToResolveOldStyle(value: String) = {
    if (value.startsWith(Variables.env)) findVariable("env", value.substring(Variables.env.length))
    else if (value.startsWith(Variables.text)) Some(value.substring(Variables.text.length))
    else Some(value)
  }

  private def tryToResolve(withVariable: String) = {
    Variables.regex
      .findAllMatchIn(withVariable)
      .foldLeft(Option(withVariable)) {
        case (Some(currentValue), regexMatch) =>
          if (regexMatch.groupCount != 3) Some(currentValue)
          else {
            val variableType = regexMatch.group(2)
            val variableName = regexMatch.group(3)
            findVariable(variableType, variableName)
              .map { value =>
                val variableGroup = regexMatch.group(1)
                currentValue.replace(variableGroup, value)
              }
          }
        case (None, _) => None
      }
  }

  private def findVariable(`type`: String, name: String): Option[String] =
    `type` match {
      case "env" => Try(Option(System.getenv(name))).toOption.flatten
      case _ => None
    }

  private object Variables {
    val regex = ".*(@\\{(.*):(.*)\\}).*".r

    val env = "env:"
    val text = "text:"
  }

}
