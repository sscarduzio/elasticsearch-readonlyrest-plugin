package tech.beshu.ror.acl.factory

import java.time.Clock

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.{Acl, AclLoggingDecorator}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason
import tech.beshu.ror.acl.utils.{JavaUuidProvider, UuidProvider}
import tech.beshu.ror.commons.settings.SettingsMalformedException

object RorAclFactoryJavaHelper extends Logging {

  private implicit val clock: Clock = Clock.systemUTC()
  private implicit val uuidProvider: UuidProvider = JavaUuidProvider
  private val aclFactory = new RorAclFactory

  def reload(httpClientFactory: HttpClientsFactory,
             settingsYaml: String): Acl = synchronized {
    aclFactory.createAclFrom(settingsYaml, httpClientFactory) match {
      case Right(acl) =>
        new AclLoggingDecorator(acl, Option.empty) // todo: add serialization tool
      case Left(errors) =>
        val errorsMessage = errors
          .map(_.reason)
          .map {
            case Reason.Message(msg) => msg
            case Reason.MalformedValue(yamlString) => s"Malformed config: $yamlString"
          }
          .toList
          .mkString("Errors:\n", "\n", "")
        throw new SettingsMalformedException(errorsMessage)
    }
  }
}
