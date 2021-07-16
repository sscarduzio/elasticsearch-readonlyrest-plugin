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

import org.elasticsearch.search.{SearchHit, SearchHitField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.fls.FieldsPolicy

import scala.collection.JavaConverters._

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
      Option(searchHit.getFields)
        .map(_.asScala.toMap)
        .filter(_.nonEmpty)
        .map(fields => filterNonMetadataDocumentFieldsOnly(fields, fieldsRestrictions))
        .foreach(allFields => searchHit.fields(allFields.asJava))

      searchHit
    }

    private def filterNonMetadataDocumentFieldsOnly(fields: Map[String, SearchHitField],
                                                    fieldsRestrictions: FieldsRestrictions) = {
      val (metadataFields, nonMetadataDocumentFields) = partitionFieldsByMetadata(fields)
      val policy = new FieldsPolicy(fieldsRestrictions)
      val filteredDocumentFields = nonMetadataDocumentFields.filter {
        case (key, _) => policy.canKeep(key)
      }
      metadataFields ++ filteredDocumentFields
    }

    private def partitionFieldsByMetadata(fields: Map[String, SearchHitField]) = {
      fields.partition {
        case t if t._2.isMetadataField => true
        case _ => false
      }
    }

  }

}
