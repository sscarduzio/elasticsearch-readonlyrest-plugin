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

import java.util

import org.elasticsearch.common.document.DocumentField
import org.elasticsearch.search.SearchHit
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.utils.ReflecUtils

import scala.util.Try
import scala.jdk.CollectionConverters.*

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
          ReflecUtils.setField(searchHit, searchHit.getClass, "documentFields", newDocumentFields.asJava)
        }
      searchHit
    }
  }

  private def extractDocumentFields(searchHit: SearchHit) = {
    Try {
      ReflecUtils.getField(searchHit, searchHit.getClass, "documentFields")
        .asInstanceOf[util.Map[String, DocumentField]]
    }
      .getOrElse(throw new IllegalStateException("Could not access document fields in search hit."))
  }
}
