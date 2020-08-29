package tech.beshu.ror.es.request

import org.elasticsearch.search.SearchHit
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions
import scala.collection.JavaConverters._

object SearchHitOps {

  implicit class Filtering(val searchHit: SearchHit) extends AnyVal {

    def modifySourceFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
      Option(searchHit.getSourceAsMap)
        .map(_.asScala.toMap)
        .filter(_.nonEmpty)
        .map(source => FieldsFiltering.provideFilteredSource(source, fieldsRestrictions))
        .foreach(newSource => searchHit.sourceRef(newSource.bytes))
      searchHit
    }

    def modifyDocumentFieldsUsing(fieldsRestrictions: FieldsRestrictions) = {
      Option(searchHit.getFields)
        .map(_.asScala.toMap)
        .filter(_.nonEmpty)
        .map(fields => FieldsFiltering.provideFilteredDocumentFields(fields, fieldsRestrictions))
        .map(newFields => newFields.documentFields ++ newFields.metadataFields)
        .foreach(allFields => searchHit.fields(allFields.asJava))
      searchHit
    }
  }

}
