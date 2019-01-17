package tech.beshu.ror.acl.factory

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.{Acl, AclLoggingDecorator}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason
import tech.beshu.ror.commons.settings.SettingsMalformedException

object RorAclFactoryJavaHelper extends Logging {

  def reload(factory: RorAclFactory, settingsYaml: String): Acl = {
    factory.createAclFrom(settingsYaml) match {
      case Right(acl) =>
        new AclLoggingDecorator(acl, Option.empty) // todo: add serialization tool
      case Left(errors) =>
        val errorsMessage = errors
          .map(_.reason)
          .map {
            case Reason.Message(msg) => msg
            case Reason.MalformedValue(json) => s"Malformed config: ${json.noSpaces}"
          }
          .toList
          .mkString("Errors:\n", "\n", "")
        throw new SettingsMalformedException(errorsMessage)
    }
  }
}
