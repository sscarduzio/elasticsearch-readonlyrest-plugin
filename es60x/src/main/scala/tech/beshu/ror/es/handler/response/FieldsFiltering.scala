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
package tech.beshu.ror.es.handler.response

import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.document.{DocumentField => ESDocumentField}
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.AccessMode
import tech.beshu.ror.fls.FieldsPolicy
import scala.collection.JavaConverters._

object FieldsFiltering {

  final case class NewFilteredSource(bytes: BytesReference)

  final case class NewFilteredDocumentFields(documentFields: Map[String, ESDocumentField],
                                             metadataFields: Map[String, ESDocumentField])

  def filterSource(sourceAsMap: Map[String, _],
                   fieldsRestrictions: FieldsRestrictions): NewFilteredSource = {
    val (excluding, including) = splitFieldsByAccessMode(fieldsRestrictions)
    val filteredSource = XContentMapValues.filter(sourceAsMap.asJava, including.toArray, excluding.toArray)
    val newContent = XContentFactory
      .contentBuilder(XContentType.JSON)
      .map(filteredSource)
    NewFilteredSource(newContent.bytes())
  }

  def filterDocumentFields(documentFields: Map[String, ESDocumentField],
                           fieldsRestrictions: FieldsRestrictions): NewFilteredDocumentFields = {
    val (metadataFields, nonMetadataDocumentFields) = partitionFieldsByMetadata(documentFields)
    val policy = new FieldsPolicy(fieldsRestrictions)
    val filteredDocumentFields = nonMetadataDocumentFields.filter {
      case (key, _) => policy.canKeep(key)
    }
    NewFilteredDocumentFields(filteredDocumentFields, metadataFields)
  }

  private def partitionFieldsByMetadata(fields: Map[String, ESDocumentField]) = {
    fields.partition {
      case t if t._2.isMetadataField => true
      case _ => false
    }
  }

  private def splitFieldsByAccessMode(fields: FieldsRestrictions) = fields.mode match {
    case AccessMode.Whitelist => (List.empty, fields.documentFields.map(_.value.value).toList)
    case AccessMode.Blacklist => (fields.documentFields.map(_.value.value).toList, List.empty)
  }
}
