/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions.utils

import org.elasticsearch.ElasticsearchStatusException

class ElasticsearchStatusExceptionOps(val ex: ElasticsearchStatusException) extends AnyVal {
  def isNotFound: Boolean = ex.status().getStatus == 404
}

object ElasticsearchStatusExceptionOps {
  implicit def toOps(ex: ElasticsearchStatusException): ElasticsearchStatusExceptionOps = new ElasticsearchStatusExceptionOps(ex)
}
