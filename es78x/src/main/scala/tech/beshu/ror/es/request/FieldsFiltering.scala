package tech.beshu.ror.es.request

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.document.{DocumentField => EDF}
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.fls.FieldsPolicy

import scala.collection.JavaConverters._

object FieldsFiltering extends Logging {

  final case class NewFilteredSource(bytes: BytesReference)
  final case class NewFilteredDocumentFields(documentFields: Map[String, EDF], metadataFields: Map[String, EDF])

  def provideFilteredSource(sourceAsMap: Map[String, _],
                            fieldsRestrictions: FieldsRestrictions): NewFilteredSource = {
    val (excluding, including) = splitFields(fieldsRestrictions)
    val filteredSource = XContentMapValues.filter(sourceAsMap.asJava, including.toArray, excluding.toArray)
    val newContent = XContentFactory
      .contentBuilder(XContentType.JSON)
      .map(filteredSource)
    NewFilteredSource(BytesReference.bytes(newContent))
  }

  def provideFilteredDocumentFields(documentFields: Map[String, EDF],
                                    fieldsRestrictions: FieldsRestrictions) = {
    val (metdataFields, nonMetadaDocumentFields) = partitionFieldsByMetadata(documentFields)
    val policy = new FieldsPolicy(fieldsRestrictions)
    val filteredDocumentFields = nonMetadaDocumentFields.filter {
      case (key, _) => policy.canKeep(key)
    }
    NewFilteredDocumentFields(filteredDocumentFields, metdataFields)
  }

  def partitionFieldsByMetadata(fields: Map[String, EDF]): (Map[String, EDF], Map[String, EDF]) = {
    fields.partition {
      case t if t._2.isMetadataField => true
      case _ => false
    }
  }

  private def splitFields(fields: FieldsRestrictions) = fields.mode match {
    case AccessMode.Whitelist => (List.empty, fields.fields.map(_.value.value).toList)
    case AccessMode.Blacklist => (fields.fields.map(_.value.value).toList, List.empty)
  }
}
