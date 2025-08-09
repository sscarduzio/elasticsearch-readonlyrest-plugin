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

import org.elasticsearch.common.document.DocumentField
import org.elasticsearch.search.SearchHit
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.Try

object SearchHitOps {

  implicit class Filtering(val searchHit: SearchHit) extends AnyVal {

    def filterSourceFieldsUsing(fieldsRestrictions: FieldsRestrictions): SearchHit = {
      Option(searchHit.getSourceAsMap)
        .map(_.asScala.toMap)
        .filter(_.nonEmpty)
        .map(source => FieldsFiltering.filterSource(source, fieldsRestrictions))
        .foreach(newSource => searchHit.sourceRef(newSource.bytes))

      searchHit
    }

    def filterDocumentFieldsUsing(fieldsRestrictions: FieldsRestrictions): SearchHit = {
      val documentFields = extractDocumentFields(searchHit)

      Option(documentFields)
        .map(fields => FieldsFiltering.NonMetadataDocumentFields(fields.asScala.toMap))
        .filter(_.value.nonEmpty)
        .map(fields => FieldsFiltering.filterNonMetadataDocumentFields(fields, fieldsRestrictions))
        .map(_.value)
        .foreach { newDocumentFields =>
          on(searchHit).set("documentFields", newDocumentFields.asJava)
        }
      searchHit
    }
  }

  private def extractDocumentFields(searchHit: SearchHit) = {
    Try(on(searchHit).get[util.Map[String, DocumentField]]("documentFields"))
      .getOrElse(throw new IllegalStateException("Could not access document fields in search hit."))
  }
}
