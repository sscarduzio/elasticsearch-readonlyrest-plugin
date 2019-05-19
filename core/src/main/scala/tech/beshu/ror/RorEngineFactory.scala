package tech.beshu.ror

import java.time.Clock

import monix.eval.Task
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CoreFactory}
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditSink, AuditingTool}
import tech.beshu.ror.acl.utils.StaticVariablesResolver
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.settings.SettingsMalformedException
import tech.beshu.ror.utils.{JavaEnvVarsProvider, JavaUuidProvider, UuidProvider}

object RorEngineFactory {

  private implicit val clock: Clock = Clock.systemUTC()
  private implicit val uuidProvider: UuidProvider = JavaUuidProvider
  private implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
  private val aclFactory = new CoreFactory

  def reload(auditSink: AuditSink,
             settingsYaml: String): Task[Engine] = synchronized {
    val httpClientsFactory = new AsyncHttpClientsFactory
    aclFactory.createCoreFrom(settingsYaml, httpClientsFactory).map {
      case Right(coreSettings) =>
        new Engine(
          new AclLoggingDecorator(
            coreSettings.aclEngine,
            coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
          ),
          coreSettings.aclStaticContext,
          httpClientsFactory
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

  final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
    def shutdown(): Unit = {
      httpClientsFactory.shutdown()
    }
  }


}
