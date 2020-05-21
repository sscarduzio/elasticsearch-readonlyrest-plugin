/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import tech.beshu.ror.es.AuditSinkService

// todo: implement
object ProxyAuditSinkService extends AuditSinkService {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = ()
}
