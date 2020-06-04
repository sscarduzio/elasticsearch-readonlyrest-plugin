/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsResponse.FieldMappingMetaData
import org.elasticsearch.action.admin.indices.mapping.get.{GetFieldMappingsRequest => AdminGetFieldMappingsRequest, GetFieldMappingsResponse => AdminGetFieldMappingsResponse}
import org.elasticsearch.client.indices.{GetFieldMappingsRequest => ClientGetFieldMappingsRequest, GetFieldMappingsResponse => ClientGetFieldMappingsResponse}
import org.joor.Reflect.{on, onClass}

import scala.collection.JavaConverters._

object GetFieldMappings {

  implicit class GetFieldMappingsRequestOps(val request: AdminGetFieldMappingsRequest) extends AnyVal {
    def toGetFieldMappingsRequest: ClientGetFieldMappingsRequest = {
      new ClientGetFieldMappingsRequest()
        .fields(request.fields(): _*)
        .indices(request.indices(): _*)
        .local(request.local())
        .indicesOptions(request.indicesOptions())
    }
  }

  implicit class GetFieldMappingsResponseOps(val response: ClientGetFieldMappingsResponse) extends AnyVal {
    def toGetFieldMappingsResponse: AdminGetFieldMappingsResponse = {
      val newMap = response
        .mappings().asScala
        .map { case (key, value) =>
          val newValue = value.asScala
            .map { case (innerKey, data) =>
              (innerKey, new FieldMappingMetaData(data.fullName(), on(data).call("getSource").get()))
            }
          (key, Map("mappings" -> newValue.asJava).asJava)
        }
        .asJava
      onClass(classOf[AdminGetFieldMappingsResponse])
        .create(newMap)
        .get[AdminGetFieldMappingsResponse]()
    }
  }
}
