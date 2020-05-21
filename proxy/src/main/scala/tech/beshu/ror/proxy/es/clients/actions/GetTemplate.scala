/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest => AdminGetIndexTemplatesRequest, GetIndexTemplatesResponse => AdminGetIndexTemplatesResponse}
import org.elasticsearch.client.indices.{GetIndexTemplatesRequest => ClientGetIndexTemplatesRequest, GetIndexTemplatesResponse => ClientGetIndexTemplatesResponse}
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.compress.CompressedXContent
import scala.collection.JavaConverters._

object GetTemplate {

  implicit class GetTemplateRequestOps(val request: AdminGetIndexTemplatesRequest) extends AnyVal {
    def toGetTemplateRequest: ClientGetIndexTemplatesRequest = {
      Option(request.names()) match {
        case Some(names) => new ClientGetIndexTemplatesRequest(names: _*)
        case None => new ClientGetIndexTemplatesRequest()
      }
    }
  }

  implicit class GetTemplateResponseOps(val response: ClientGetIndexTemplatesResponse) extends AnyVal {
    def toGetTemplateResponse: AdminGetIndexTemplatesResponse = {
      val metadataList = response
        .getIndexTemplates.asScala
        .map { metadata =>
          new IndexTemplateMetaData(
            metadata.name(),
            metadata.order(),
            metadata.version(),
            metadata.patterns(),
            metadata.settings(),
            Option(metadata.mappings()) match {
              case Some(mappings) =>
                val builder = ImmutableOpenMap.builder[String, CompressedXContent]()
                builder.put(mappings.`type`(), mappings.source())
                builder.build()
              case None =>
                ImmutableOpenMap.builder().build()
            },
            metadata.aliases()
          )
        }
      new AdminGetIndexTemplatesResponse(metadataList.asJava)
    }
  }
}
