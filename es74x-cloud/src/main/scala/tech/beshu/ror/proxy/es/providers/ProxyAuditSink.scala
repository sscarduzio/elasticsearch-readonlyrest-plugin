/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.providers

import tech.beshu.ror.es.AuditSink

// todo: implement
object ProxyAuditSink extends AuditSink {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = ()
}
