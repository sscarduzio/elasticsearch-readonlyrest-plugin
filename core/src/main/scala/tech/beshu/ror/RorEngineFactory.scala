package tech.beshu.ror

import monix.eval.Task
import tech.beshu.ror.acl.factory.AsyncHttpClientsFactory
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.es.AuditSink

// todo: to remove
object RorEngineFactory {

  def reload(auditSink: AuditSink,
             settingsYaml: String): Task[Engine] = ???

  final class Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
    def shutdown(): Unit = {
      httpClientsFactory.shutdown()
    }
  }


}
