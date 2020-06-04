/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.client.GetAliasesResponse
import org.joor.Reflect.onClass
import tech.beshu.ror.proxy.es.exceptions._

object GetAliases {

  implicit class GetAliasesResponseOps(val response: GetAliasesResponse) extends AnyVal {
    def toResponseWithSpecializedException: GetAliasesResponse = {
      Option(response.getException) match {
        case Some(ex) =>
          onClass(classOf[GetAliasesResponse])
            .create(response.status(), ex.toSpecializedException)
            .get[GetAliasesResponse]()
        case None =>
          response
      }
    }
  }
}
