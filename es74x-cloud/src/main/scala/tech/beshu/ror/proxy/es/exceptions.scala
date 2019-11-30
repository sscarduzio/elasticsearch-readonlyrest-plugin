/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import org.elasticsearch.ElasticsearchSecurityException
import org.elasticsearch.rest.RestStatus

object exceptions {

  case object NotDefinedForRorProxy extends ElasticsearchSecurityException("Invalid action for ROR proxy", RestStatus.NOT_IMPLEMENTED)
}
