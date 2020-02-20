/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import org.elasticsearch.ElasticsearchSecurityException
import org.elasticsearch.rest.RestStatus

object exceptions {

  case object NotDefinedForRorProxy extends ElasticsearchSecurityException("Invalid action for ROR proxy", RestStatus.NOT_IMPLEMENTED)

  final case class RorProxyException(message: String, cause: Throwable) extends Exception(message, cause) {
    def this(message: String) {
      this(message, null)
    }
  }
  object RorProxyException {
    def wrap(throwable: Throwable): RorProxyException = RorProxyException("ROR proxy internal error", throwable)
  }
}
