package tech.beshu.ror.acl.factory

import java.time.Clock

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.{Acl, AclLoggingDecorator, AclStaticContext}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.commons.settings.SettingsMalformedException

object RorEngineFactory extends Logging {

  private implicit val clock: Clock = Clock.systemUTC()
  private implicit val uuidProvider: UuidProvider = JavaUuidProvider
  private implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
  private val aclFactory = new RorAclFactory

  def reload(httpClientFactory: HttpClientsFactory,
             settingsYaml: String): Engine = synchronized {
    aclFactory.createAclFrom(settingsYaml, httpClientFactory) match {
      case Right((acl, context)) =>
        Engine(
          new AclLoggingDecorator(acl, Option.empty), // todo: add serialization tool
          context
        )
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

  final case class Engine(acl: Acl, context: AclStaticContext)

}
