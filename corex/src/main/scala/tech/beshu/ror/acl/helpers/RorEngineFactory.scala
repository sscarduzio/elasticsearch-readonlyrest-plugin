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
package tech.beshu.ror.acl.helpers

import java.time.Clock

import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason
import tech.beshu.ror.acl.factory.{AsyncHttpClientsFactory, CoreFactory}
import tech.beshu.ror.acl.logging.{AclLoggingDecorator, AuditSink, AuditingTool}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.settings.SettingsMalformedException

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

  def forkJoin = Scheduler.forkJoin(10, 100)

}
