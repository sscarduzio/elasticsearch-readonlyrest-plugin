package tech.beshu.ror.acl.factory

import java.time.Clock

import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditSink, AuditingTool}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.settings.SettingsMalformedException

object RorEngineFactory {

  private implicit val clock: Clock = Clock.systemUTC()
  private implicit val uuidProvider: UuidProvider = JavaUuidProvider
  private implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
  private val aclFactory = new RorAclFactory

  def reload(httpClientFactory: HttpClientsFactory,
             auditSink: AuditSink,
             settingsYaml: String): Engine = synchronized {
    aclFactory.createCoreFrom(settingsYaml, httpClientFactory) match {
      case Right(coreSettings) =>
        Engine(
          new AclLoggingDecorator(
            coreSettings.aclEngine,
            coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
          ),
          coreSettings.aclStaticContext
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
