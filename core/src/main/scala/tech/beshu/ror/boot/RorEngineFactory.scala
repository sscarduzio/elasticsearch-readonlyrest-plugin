package tech.beshu.ror.boot

import monix.eval.Task
import tech.beshu.ror.acl.factory.AsyncHttpClientsFactory
import tech.beshu.ror.acl.{Acl, AclStaticContext}
import tech.beshu.ror.es.AuditSink

// todo: to remove
object RorEngineFactory {

  def reload(auditSink: AuditSink,
             settingsYaml: String): Task[__old_Engine] = ???

  final class __old_Engine(val acl: Acl, val context: AclStaticContext, httpClientsFactory: AsyncHttpClientsFactory) {
    def shutdown(): Unit = {
      httpClientsFactory.shutdown()
    }
  }


}
