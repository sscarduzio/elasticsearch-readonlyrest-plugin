package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.mapping.get.{GetMappingsRequest => AdminGetMappingsRequest, GetMappingsResponse => AdminGetMappingsResponse}
import org.elasticsearch.client.indices.{GetMappingsRequest => ClientGetMappingsRequest, GetMappingsResponse => ClientGetMappingsResponse}
import org.elasticsearch.common.collect.ImmutableOpenMap

import scala.collection.JavaConverters._

object GetMappings {

  implicit class GetMappingsRequestOps(val request: AdminGetMappingsRequest) extends AnyVal {
    def toGetMappingsRequest: ClientGetMappingsRequest = {
      new ClientGetMappingsRequest()
        .indices(request.indices(): _*)
        .local(request.local())
    }
  }

  implicit class GetMappingsResponseOps(val response: ClientGetMappingsResponse) extends AnyVal {
    def toGetMappingsResponse: AdminGetMappingsResponse = {
      val newMap = ImmutableOpenMap
        .builder()
        .putAll {
          response
            .mappings().asScala
            .map { case (key, value) =>
              val mappingsMap = ImmutableOpenMap
                .builder()
                .fPut("mappings", value)
                .build()
              (key, mappingsMap)
            }
            .asJava
        }
        .build()
      new AdminGetMappingsResponse(newMap)
    }
  }
}
