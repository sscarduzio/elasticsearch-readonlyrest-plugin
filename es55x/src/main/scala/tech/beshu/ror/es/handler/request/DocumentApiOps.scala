/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.handler.request

import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.index.get.{GetField, GetResult}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.{DocumentId, DocumentWithIndex, ClusterIndexName}
import tech.beshu.ror.fls.FieldsPolicy

import scala.collection.JavaConverters._

object DocumentApiOps {

  object GetApi {

    //it's ugly but I don't know better way to do it
    def doesNotExistResponse(original: GetResponse) = {
      val exists = false
      val source = null
      val result = new GetResult(
        original.getIndex,
        original.getType,
        original.getId,
        original.getVersion,
        exists,
        source,
        java.util.Collections.emptyMap())
      new GetResponse(result)
    }

    implicit class GetResponseOps(val response: GetResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(response.getIndex, response.getId)

      def filterFieldsUsing(fieldsRestrictions: FieldsRestrictions): GetResponse = {
        val newSource = filterSourceFieldsUsing(fieldsRestrictions)
        val newFields = filterDocumentFieldsUsing(fieldsRestrictions)

        val newResult = new GetResult(
          response.getIndex,
          response.getType,
          response.getId,
          response.getVersion,
          true,
          newSource,
          newFields.asJava
        )
        new GetResponse(newResult)
      }

      private def filterSourceFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
        Option(response.getSourceAsMap)
          .map(_.asScala.toMap)
          .filter(_.nonEmpty)
          .map(source => FieldsFiltering.filterSource(source, fieldsRestrictions)) match {
          case Some(value) => value.bytes
          case None => response.getSourceAsBytesRef
        }
      }

      private def filterDocumentFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
        val (metadataFields, nonMetadataDocumentFields) = partitionFieldsByMetadata(response.getFields.asScala.toMap)
        val policy = new FieldsPolicy(fieldsRestrictions)
        val filteredDocumentFields = nonMetadataDocumentFields.filter {
          case (key, _) => policy.canKeep(key)
        }
        metadataFields ++ filteredDocumentFields
      }

      private def partitionFieldsByMetadata(fields: Map[String, GetField]) = {
        fields.partition {
          case t if t._2.isMetadataField => true
          case _ => false
        }
      }
    }
  }

  object MultiGetApi {
    implicit class MultiGetItemResponseOps(val item: MultiGetItemResponse) extends AnyVal {
      def asDocumentWithIndex = createDocumentWithIndex(item.getIndex, item.getId)
    }
  }

  private def createDocumentWithIndex(indexStr: String, docId: String) = {
    val indexName = createIndexName(indexStr)
    val documentId = DocumentId(docId)
    DocumentWithIndex(indexName, documentId)
  }

  private def createIndexName(indexStr: String) = {
    ClusterIndexName
      .fromString(indexStr)
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }
}